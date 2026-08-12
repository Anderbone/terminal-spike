/*
 * Android pipe/JNI frontend for the pinned Mosh 1.4.0 transport engine.
 * Copyright 2026 Terminal Spike contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Modified 2026-08-09. This replaces the process-global CLI frontend with
 * one explicitly owned native session in each isolated Android worker process.
 */
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <clocale>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <functional>
#include <memory>
#include <mutex>
#include <poll.h>
#include <signal.h>
#include <string>
#include <sys/prctl.h>
#include <unistd.h>
#include <vector>

#include "completeterminal.h"
#include "crypto.h"
#include "fatal_assert.h"
#include "networktransport-impl.h"
#include "parseraction.h"
#include "terminaldisplay.h"
#include "terminalframebuffer.h"
#include "terminaloverlay.h"
#include "timestamp.h"
#include "user.h"

namespace {

constexpr int kRemoteClosed = 0;
constexpr int kCancelled = 1;
constexpr int kKeyReadFailed = 2;
constexpr int kUdpTimeout = 3;
constexpr int kInitializationFailed = 4;
constexpr int kTerminalPipeClosed = 5;
constexpr int kAlreadyRunning = 6;
constexpr int kInternalError = 7;
constexpr uint64_t kInitialResponseTimeoutMs = 15'000;
constexpr uint64_t kGracefulShutdownMs = 1'000;
constexpr size_t kMaximumPendingOutput = 8 * 1024 * 1024;

using MoshTransport = Network::Transport<Network::UserStream, Terminal::Complete>;

class OwnedFd {
 public:
  explicit OwnedFd( int fd = -1 ) : fd_( fd ) {}
  ~OwnedFd() { reset(); }
  OwnedFd( const OwnedFd & ) = delete;
  OwnedFd &operator=( const OwnedFd & ) = delete;
  int get() const { return fd_; }
  void reset( int fd = -1 ) {
    if ( fd_ >= 0 ) {
      while ( close( fd_ ) < 0 && errno == EINTR ) {}
    }
    fd_ = fd;
  }

 private:
  int fd_;
};

int duplicate_borrowed_fd( const int fd ) {
  int duplicate;
  do {
    duplicate = fcntl( fd, F_DUPFD_CLOEXEC, 0 );
  } while ( duplicate < 0 && errno == EINTR );
  return duplicate;
}

struct SessionControl {
  std::string session_id;
  OwnedFd wake_read;
  OwnedFd wake_write;
  std::atomic<bool> stop_requested { false };
  std::atomic<int> columns { 0 };
  std::atomic<int> rows { 0 };
  std::atomic<uint64_t> resize_generation { 0 };
  std::atomic<uint64_t> connectivity_generation { 0 };
};

std::mutex g_control_mutex;
SessionControl *g_control = nullptr;

void secure_clear( void *memory, size_t size ) {
  volatile unsigned char *cursor = static_cast<volatile unsigned char *>( memory );
  while ( size-- ) *cursor++ = 0;
}

bool set_nonblocking( int fd ) {
  const int old_flags = fcntl( fd, F_GETFL );
  return old_flags >= 0 && fcntl( fd, F_SETFL, old_flags | O_NONBLOCK ) == 0;
}

void wake( SessionControl &control ) {
  const unsigned char byte = 1;
  ssize_t ignored;
  do {
    ignored = write( control.wake_write.get(), &byte, sizeof byte );
  } while ( ignored < 0 && errno == EINTR );
}

void drain_wake_pipe( int fd ) {
  unsigned char buffer[ 64 ];
  while ( true ) {
    const ssize_t count = read( fd, buffer, sizeof buffer );
    if ( count > 0 ) continue;
    if ( count < 0 && errno == EINTR ) continue;
    break;
  }
}

uint64_t monotonic_millis() {
  struct timespec now {};
  if ( clock_gettime( CLOCK_MONOTONIC, &now ) != 0 ) return 0;
  return static_cast<uint64_t>( now.tv_sec ) * 1000 + now.tv_nsec / 1'000'000;
}

bool read_one_shot_key( int key_fd, int wake_fd, const std::atomic<bool> &stopped,
                        char key[ 23 ] ) {
  size_t offset = 0;
  const uint64_t deadline = monotonic_millis() + 5'000;
  while ( offset < 22 ) {
    if ( stopped.load( std::memory_order_acquire ) ) return false;
    const uint64_t now = monotonic_millis();
    if ( now >= deadline ) return false;
    struct pollfd descriptors[ 2 ] = {
      { key_fd, POLLIN | POLLHUP, 0 },
      { wake_fd, POLLIN | POLLHUP, 0 },
    };
    const int wait_ms = static_cast<int>( std::min<uint64_t>( deadline - now, 1'000 ) );
    int ready;
    do {
      ready = poll( descriptors, 2, wait_ms );
    } while ( ready < 0 && errno == EINTR );
    if ( ready < 0 ) return false;
    if ( descriptors[ 1 ].revents ) {
      drain_wake_pipe( wake_fd );
      if ( stopped.load( std::memory_order_acquire ) ) return false;
    }
    if ( !(descriptors[ 0 ].revents & (POLLIN | POLLHUP)) ) continue;
    ssize_t count;
    do {
      count = read( key_fd, key + offset, 22 - offset );
    } while ( count < 0 && errno == EINTR );
    if ( count <= 0 ) return false;
    offset += static_cast<size_t>( count );
  }
  key[ 22 ] = '\0';

  // Require EOF after the fixed 22-character protocol value. This prevents a
  // valid prefix from hiding an ambiguous or accidentally reused key payload.
  struct pollfd descriptor { key_fd, POLLIN | POLLHUP, 0 };
  int ready;
  do {
    ready = poll( &descriptor, 1, 1'000 );
  } while ( ready < 0 && errno == EINTR );
  if ( ready <= 0 || !(descriptor.revents & (POLLIN | POLLHUP)) ) return false;
  unsigned char extra = 0;
  ssize_t count;
  do {
    count = read( key_fd, &extra, 1 );
  } while ( count < 0 && errno == EINTR );
  return count == 0;
}

class UtfChars {
 public:
  UtfChars( JNIEnv *environment, jstring value )
    : environment_( environment ), value_( value ), chars_( nullptr ) {
    if ( value_ != nullptr ) chars_ = environment_->GetStringUTFChars( value_, nullptr );
  }
  ~UtfChars() {
    if ( chars_ != nullptr ) environment_->ReleaseStringUTFChars( value_, chars_ );
  }
  const char *get() const { return chars_; }

 private:
  JNIEnv *environment_;
  jstring value_;
  const char *chars_;
};

void notify_connected( JNIEnv *environment, jobject listener, uint64_t generation ) {
  jclass listener_class = environment->GetObjectClass( listener );
  if ( listener_class == nullptr ) {
    environment->ExceptionClear();
    return;
  }
  jmethodID callback = environment->GetMethodID( listener_class, "onConnected", "(J)V" );
  if ( callback != nullptr ) {
    environment->CallVoidMethod( listener, callback, static_cast<jlong>( generation ) );
  }
  environment->DeleteLocalRef( listener_class );
  if ( environment->ExceptionCheck() ) environment->ExceptionClear();
}

int run_transport( JNIEnv *environment,
                   SessionControl &control,
                   const char *server_address,
                   int udp_port,
                   OwnedFd &key_fd,
                   OwnedFd &terminal_input,
                   OwnedFd &terminal_output,
                   int initial_columns,
                   int initial_rows,
                   jobject listener ) {
  char printable_key[ 23 ] {};
  if ( !read_one_shot_key( key_fd.get(), control.wake_read.get(),
                           control.stop_requested, printable_key ) ) {
    secure_clear( printable_key, sizeof printable_key );
    return control.stop_requested.load() ? kCancelled : kKeyReadFailed;
  }
  key_fd.reset();

  freeze_timestamp();
  Network::UserStream initial_user_state;
  Terminal::Complete initial_terminal( initial_columns, initial_rows );
  std::unique_ptr<MoshTransport> transport;
  try {
    const std::string port = std::to_string( udp_port );
    transport.reset( new MoshTransport(
      initial_user_state,
      initial_terminal,
      printable_key,
      server_address,
      port.c_str() ) );
  } catch ( const Crypto::CryptoException & ) {
    secure_clear( printable_key, sizeof printable_key );
    return kKeyReadFailed;
  } catch ( const std::exception & ) {
    secure_clear( printable_key, sizeof printable_key );
    return kInitializationFailed;
  }
  secure_clear( printable_key, sizeof printable_key );

  transport->set_send_delay( 1 );
  transport->get_current_state().push_back( Parser::Resize( initial_columns, initial_rows ) );

  Terminal::Display display( false );
  Terminal::Framebuffer local_framebuffer( initial_columns, initial_rows );
  // Keep the upstream STMClient prediction path: input is still authoritative only after the
  // server acknowledges it, while the predicted framebuffer makes interactive typing visible.
  Overlay::PredictionEngine predictions;
  // Android terminal input must remain visibly responsive during transient packet loss. Adaptive
  // mode deliberately suppresses prediction on low-latency links, which means a brief lost ACK can
  // leave typed characters invisible until the server catches up. Keep server state authoritative,
  // but always render Mosh's upstream conditional prediction overlay while input is outstanding.
  predictions.set_display_preference( Overlay::PredictionEngine::Always );
  std::string pending_output;
  size_t pending_offset = 0;
  bool display_initialized = false;
  uint64_t applied_resize_generation = 0;
  uint64_t notified_connectivity_generation = UINT64_MAX;
  const uint64_t startup_timestamp = frozen_timestamp();
  uint64_t shutdown_timestamp = 0;
  bool connected = false;
  bool input_closed = false;

  while ( true ) {
    freeze_timestamp();

    const uint64_t resize_generation = control.resize_generation.load( std::memory_order_acquire );
    if ( resize_generation != applied_resize_generation && !transport->shutdown_in_progress() ) {
      const int columns = control.columns.load( std::memory_order_relaxed );
      const int rows = control.rows.load( std::memory_order_relaxed );
      if ( columns > 0 && rows > 0 ) {
        transport->get_current_state().push_back( Parser::Resize( columns, rows ) );
        predictions.reset();
      }
      applied_resize_generation = resize_generation;
    }

    if ( control.stop_requested.load( std::memory_order_acquire ) ) {
      if ( !transport->shutdown_in_progress() && transport->has_remote_addr() ) {
        transport->start_shutdown();
        shutdown_timestamp = frozen_timestamp();
      } else if ( !transport->has_remote_addr() ) {
        return kCancelled;
      }
    }

    if ( connected ) {
      Terminal::Framebuffer frame = transport->get_latest_remote_state().state.get_fb();
      predictions.cull( frame );
      predictions.apply( frame );
      std::string output = display.new_frame( display_initialized, local_framebuffer, frame );
      if ( pending_output.size() - pending_offset + output.size() > kMaximumPendingOutput ) {
        return kTerminalPipeClosed;
      }
      if ( pending_offset > 1024 * 1024 ) {
        pending_output.erase( 0, pending_offset );
        pending_offset = 0;
      }
      pending_output.append( output );
      local_framebuffer = frame;
      display_initialized = true;
    }

    std::vector<int> network_fds = transport->fds();
    std::vector<struct pollfd> descriptors;
    descriptors.reserve( network_fds.size() + 3 );
    for ( int fd : network_fds ) descriptors.push_back( { fd, POLLIN, 0 } );
    const size_t input_index = descriptors.size();
    descriptors.push_back( { terminal_input.get(), POLLIN | POLLHUP, 0 } );
    const size_t wake_index = descriptors.size();
    descriptors.push_back( { control.wake_read.get(), POLLIN | POLLHUP, 0 } );
    const size_t output_index = descriptors.size();
    descriptors.push_back( {
      terminal_output.get(),
      pending_offset < pending_output.size() ? static_cast<short>( POLLOUT ) : static_cast<short>( 0 ),
      0,
    } );

    int wait_ms = std::clamp(
      std::min( transport->wait_time(), predictions.wait_time() ), 0, 1'000 );
    if ( !connected ) wait_ms = std::min( wait_ms, 250 );
    int ready;
    do {
      ready = poll( descriptors.data(), descriptors.size(), wait_ms );
    } while ( ready < 0 && errno == EINTR );
    freeze_timestamp();
    if ( ready < 0 ) return kInternalError;

    if ( descriptors[ wake_index ].revents ) drain_wake_pipe( control.wake_read.get() );

    bool network_ready = false;
    for ( size_t index = 0; index < network_fds.size(); ++index ) {
      if ( descriptors[ index ].revents & POLLIN ) network_ready = true;
    }
    if ( network_ready ) {
      try {
        transport->recv();
        connected = true;
        predictions.set_local_frame_acked( transport->get_sent_state_acked() );
        predictions.set_send_interval( transport->send_interval() );
        predictions.set_local_frame_late_acked(
          transport->get_latest_remote_state().state.get_echo_ack() );
        const uint64_t generation = control.connectivity_generation.load( std::memory_order_acquire );
        if ( generation != notified_connectivity_generation ) {
          notify_connected( environment, listener, generation );
          notified_connectivity_generation = generation;
        }
      } catch ( const Crypto::CryptoException &error ) {
        if ( error.fatal ) return kInternalError;
        // Unauthenticated or corrupt UDP packets are deliberately ignored.
      } catch ( const Network::NetworkException & ) {
        // UDP and interface failures are transient after bootstrap; Mosh keeps
        // its transport state and probes through subsequent port hops.
      }
    }

    if ( descriptors[ input_index ].revents & (POLLIN | POLLHUP) ) {
      unsigned char input[ 16 * 1024 ];
      ssize_t count;
      do {
        count = read( terminal_input.get(), input, sizeof input );
      } while ( count < 0 && errno == EINTR );
      if ( count > 0 && !transport->shutdown_in_progress() ) {
        predictions.set_local_frame_sent( transport->get_sent_state_last() );
        const bool paste = count > 100;
        if ( paste ) predictions.reset();
        for ( ssize_t index = 0; index < count; ++index ) {
          if ( !paste ) {
            predictions.new_user_byte( static_cast<char>( input[ index ] ), local_framebuffer );
          }
          transport->get_current_state().push_back( Parser::UserByte( input[ index ] ) );
        }
      } else if ( count == 0 ) {
        input_closed = true;
      } else if ( count < 0 && errno != EAGAIN && errno != EWOULDBLOCK ) {
        input_closed = true;
      }
    }

    if ( descriptors[ output_index ].revents & (POLLERR | POLLHUP | POLLNVAL) ) {
      return control.stop_requested.load() ? kCancelled : kTerminalPipeClosed;
    }
    if ( descriptors[ output_index ].revents & POLLOUT ) {
      ssize_t count;
      do {
        count = write(
          terminal_output.get(),
          pending_output.data() + pending_offset,
          pending_output.size() - pending_offset );
      } while ( count < 0 && errno == EINTR );
      if ( count > 0 ) {
        pending_offset += static_cast<size_t>( count );
        if ( pending_offset == pending_output.size() ) {
          pending_output.clear();
          pending_offset = 0;
        }
      } else if ( count < 0 && errno != EAGAIN && errno != EWOULDBLOCK ) {
        return control.stop_requested.load() ? kCancelled : kTerminalPipeClosed;
      }
    }

    if ( input_closed ) {
      return control.stop_requested.load() ? kCancelled : kTerminalPipeClosed;
    }

    try {
      transport->tick();
    } catch ( const Crypto::CryptoException &error ) {
      if ( error.fatal ) return kInternalError;
    } catch ( const Network::NetworkException & ) {
      // See recv(): a network change is a roaming condition, not session loss.
    }

    if ( transport->counterparty_shutdown_ack_sent() ) return kRemoteClosed;
    if ( transport->shutdown_in_progress() && transport->shutdown_acknowledged() ) {
      return control.stop_requested.load() ? kCancelled : kRemoteClosed;
    }
    if ( control.stop_requested.load() && shutdown_timestamp != 0 &&
         frozen_timestamp() - shutdown_timestamp >= kGracefulShutdownMs ) {
      return kCancelled;
    }
    if ( !connected && frozen_timestamp() - startup_timestamp >= kInitialResponseTimeoutMs ) {
      return kUdpTimeout;
    }
  }
}

void update_control( const char *session_id, const std::function<void( SessionControl & )> &update ) {
  std::lock_guard<std::mutex> lock( g_control_mutex );
  if ( g_control != nullptr && g_control->session_id == session_id ) {
    update( *g_control );
    wake( *g_control );
  }
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_yanjiyu_terminalspike_mosh_MoshNativeBridge_nativeVersion(
  JNIEnv *environment,
  jobject /* receiver */ ) {
  return environment->NewStringUTF( "mosh-1.4.0" );
}

extern "C" JNIEXPORT jint JNICALL
Java_com_yanjiyu_terminalspike_mosh_MoshNativeBridge_nativeRunSession(
  JNIEnv *environment,
  jobject /* receiver */,
  jstring session_id_value,
  jstring server_address_value,
  jint udp_port,
  jint key_read_fd,
  jint terminal_input_read_fd,
  jint terminal_output_write_fd,
  jint initial_columns,
  jint initial_rows,
  jobject listener ) {
  UtfChars session_id( environment, session_id_value );
  UtfChars server_address( environment, server_address_value );
  if ( session_id.get() == nullptr || server_address.get() == nullptr || listener == nullptr ||
       udp_port < 1 || udp_port > 65'535 ||
       initial_columns < 1 || initial_columns > 1'000 ||
       initial_rows < 1 || initial_rows > 1'000 ||
       key_read_fd < 0 || terminal_input_read_fd < 0 || terminal_output_write_fd < 0 ) {
    if ( environment->ExceptionCheck() ) environment->ExceptionClear();
    return kInitializationFailed;
  }

  // Java retains ownership of the Binder PFDs for the duration of this call. Duplicate every
  // borrowed descriptor before native work so Java can close its originals even if loading or JNI
  // dispatch fails, while these RAII owners close every complete or partial duplication here.
  OwnedFd key_fd( duplicate_borrowed_fd( key_read_fd ) );
  OwnedFd terminal_input( duplicate_borrowed_fd( terminal_input_read_fd ) );
  OwnedFd terminal_output( duplicate_borrowed_fd( terminal_output_write_fd ) );
  if ( key_fd.get() < 0 || terminal_input.get() < 0 || terminal_output.get() < 0 ) {
    return kInitializationFailed;
  }

  int wake_descriptors[ 2 ] { -1, -1 };
  if ( pipe2( wake_descriptors, O_CLOEXEC | O_NONBLOCK ) != 0 ||
       !set_nonblocking( terminal_input.get() ) ||
       !set_nonblocking( terminal_output.get() ) ) {
    if ( wake_descriptors[ 0 ] >= 0 ) close( wake_descriptors[ 0 ] );
    if ( wake_descriptors[ 1 ] >= 0 ) close( wake_descriptors[ 1 ] );
    return kInitializationFailed;
  }

  SessionControl control;
  control.session_id = session_id.get();
  control.wake_read.reset( wake_descriptors[ 0 ] );
  control.wake_write.reset( wake_descriptors[ 1 ] );
  control.columns.store( initial_columns );
  control.rows.store( initial_rows );

  {
    std::lock_guard<std::mutex> lock( g_control_mutex );
    if ( g_control != nullptr ) return kAlreadyRunning;
    g_control = &control;
  }

  // Each worker process owns exactly one Mosh session. These process-global
  // choices therefore cannot interfere with another active session.
  prctl( PR_SET_DUMPABLE, 0, 0, 0, 0 );
  signal( SIGPIPE, SIG_IGN );
  if ( setlocale( LC_CTYPE, "C.UTF-8" ) == nullptr ) {
    std::lock_guard<std::mutex> lock( g_control_mutex );
    g_control = nullptr;
    return kInitializationFailed;
  }

  int result = kInternalError;
  try {
    result = run_transport(
      environment,
      control,
      server_address.get(),
      udp_port,
      key_fd,
      terminal_input,
      terminal_output,
      initial_columns,
      initial_rows,
      listener );
  } catch ( const std::exception & ) {
    result = kInternalError;
  } catch ( ... ) {
    result = kInternalError;
  }
  {
    std::lock_guard<std::mutex> lock( g_control_mutex );
    if ( g_control == &control ) g_control = nullptr;
  }
  return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_yanjiyu_terminalspike_mosh_MoshNativeBridge_nativeResize(
  JNIEnv *environment,
  jobject /* receiver */,
  jstring session_id_value,
  jint columns,
  jint rows ) {
  UtfChars session_id( environment, session_id_value );
  if ( session_id.get() == nullptr || columns < 1 || columns > 1'000 || rows < 1 || rows > 1'000 ) {
    if ( environment->ExceptionCheck() ) environment->ExceptionClear();
    return;
  }
  update_control( session_id.get(), [columns, rows]( SessionControl &control ) {
    control.columns.store( columns, std::memory_order_relaxed );
    control.rows.store( rows, std::memory_order_relaxed );
    control.resize_generation.fetch_add( 1, std::memory_order_release );
  } );
}

extern "C" JNIEXPORT void JNICALL
Java_com_yanjiyu_terminalspike_mosh_MoshNativeBridge_nativeUpdateNetworkHint(
  JNIEnv *environment,
  jobject /* receiver */,
  jstring session_id_value,
  jlong connectivity_generation ) {
  UtfChars session_id( environment, session_id_value );
  if ( session_id.get() == nullptr || connectivity_generation < 0 ) {
    if ( environment->ExceptionCheck() ) environment->ExceptionClear();
    return;
  }
  update_control( session_id.get(), [connectivity_generation]( SessionControl &control ) {
    control.connectivity_generation.store(
      static_cast<uint64_t>( connectivity_generation ),
      std::memory_order_release );
  } );
}

extern "C" JNIEXPORT void JNICALL
Java_com_yanjiyu_terminalspike_mosh_MoshNativeBridge_nativeStop(
  JNIEnv *environment,
  jobject /* receiver */,
  jstring session_id_value ) {
  UtfChars session_id( environment, session_id_value );
  if ( session_id.get() == nullptr ) {
    if ( environment->ExceptionCheck() ) environment->ExceptionClear();
    return;
  }
  update_control( session_id.get(), []( SessionControl &control ) {
    control.stop_requested.store( true, std::memory_order_release );
  } );
}
