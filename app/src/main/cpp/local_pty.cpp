// Copyright 2026 Terminal Spike contributors
// SPDX-License-Identifier: GPL-3.0-or-later
// Original PTY/process ownership bridge. Contains no terminal application source.
#include <jni.h>
#include <cerrno>
#include <chrono>
#include <csignal>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <mutex>
#include <poll.h>
#include <string>
#include <sys/eventfd.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/wait.h>
#include <termios.h>
#include <thread>
#include <unistd.h>
#include <unordered_map>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;

void fail(JNIEnv *env, const char *operation, int error = errno) {
    std::string message = std::string(operation) + ": " + strerror(error);
    env->ThrowNew(env->FindClass("java/io/IOException"), message.c_str());
}

struct Pty {
    int master = -1;
    int wake = -1;
    pid_t child = -1;
    std::mutex state;
    std::mutex writer;
    bool closed = false;
    int exit = -1;

    // state must be locked. Reaping and signalling use the same lock so a reaped
    // PID is never subsequently signalled after the kernel reuses it.
    int reap() {
        if (exit >= 0 || child <= 0) return exit;
        int status = 0;
        pid_t result;
        do { result = waitpid(child, &status, WNOHANG); } while (result < 0 && errno == EINTR);
        if (result == child) {
            exit = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
            child = -1;
        } else if (result < 0 && errno == ECHILD) {
            child = -1;
            exit = 255;
        }
        return exit;
    }

    void signalChild(int signal) {
        std::lock_guard<std::mutex> guard(state);
        reap();
        if (child > 0) kill(child, signal);
    }

    ~Pty() {
        // All I/O operations retain a shared_ptr. Descriptors cannot be recycled
        // until the last operation finishes, even if close removed the handle.
        if (master >= 0) ::close(master);
        if (wake >= 0) ::close(wake);
        if (child > 0) {
            kill(child, SIGKILL);
            const pid_t remaining = child;
            std::thread([remaining] {
                int status;
                while (waitpid(remaining, &status, 0) < 0 && errno == EINTR) {}
            }).detach();
        }
    }
};

std::mutex handlesLock;
std::unordered_map<jlong, std::shared_ptr<Pty>> handles;
jlong nextHandle = 1;

std::shared_ptr<Pty> lookup(jlong handle) {
    std::lock_guard<std::mutex> guard(handlesLock);
    auto found = handles.find(handle);
    return found == handles.end() ? nullptr : found->second;
}

std::vector<std::string> strings(JNIEnv *env, jobjectArray array) {
    std::vector<std::string> result;
    for (jsize i = 0; i < env->GetArrayLength(array); ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        const char *utf = env->GetStringUTFChars(value, nullptr);
        if (!utf) return {};
        result.emplace_back(utf);
        env->ReleaseStringUTFChars(value, utf);
        env->DeleteLocalRef(value);
    }
    return result;
}

std::vector<char *> pointers(std::vector<std::string> &values) {
    std::vector<char *> result;
    for (auto &value : values) result.push_back(value.data());
    result.push_back(nullptr);
    return result;
}

// Called only after fork. No JNI, C++ allocation, locks or stdio here.
[[noreturn]] void childFailure(int pipe) {
    const int error = errno;
    ssize_t result;
    do { result = ::write(pipe, &error, sizeof(error)); } while (result < 0 && errno == EINTR);
    _exit(127);
}
}

#define JNI_METHOD(name) Java_com_yanjiyu_terminalspike_localarch_PtyNative_##name

extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(start)(
    JNIEnv *env, jobject, jobjectArray jargv, jobjectArray jenv, jstring jcwd,
    jint columns, jint rows) {
    auto argvStorage = strings(env, jargv);
    auto envStorage = strings(env, jenv);
    if (env->ExceptionCheck()) return 0;
    if (argvStorage.empty() || columns < 1 || rows < 1 || columns > 500 || rows > 500) {
        fail(env, "Invalid local terminal launch", EINVAL); return 0;
    }
    auto argv = pointers(argvStorage);
    auto environ = pointers(envStorage);
    const char *cwdUtf = env->GetStringUTFChars(jcwd, nullptr);
    if (!cwdUtf) return 0;
    const std::string cwd(cwdUtf);
    env->ReleaseStringUTFChars(jcwd, cwdUtf);
    auto pty = std::make_shared<Pty>();
    pty->master = open("/dev/ptmx", O_RDWR | O_NOCTTY | O_CLOEXEC | O_NONBLOCK);
    if (pty->master < 0) { fail(env, "Open PTY"); return 0; }
    if (grantpt(pty->master) || unlockpt(pty->master)) { fail(env, "Unlock PTY"); return 0; }
    char slaveName[128];
    int error = ptsname_r(pty->master, slaveName, sizeof(slaveName));
    if (error) { fail(env, "Resolve PTY", error); return 0; }
    int slave = open(slaveName, O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (slave < 0) { fail(env, "Open PTY slave"); return 0; }
    struct winsize size{};
    size.ws_col = static_cast<unsigned short>(columns);
    size.ws_row = static_cast<unsigned short>(rows);
    struct termios modes{};
    if (tcgetattr(slave, &modes) < 0) { error = errno; ::close(slave); fail(env, "PTY modes", error); return 0; }
    modes.c_iflag |= ICRNL | IXON | IUTF8;
    modes.c_oflag |= OPOST | ONLCR;
    modes.c_lflag |= ISIG | ICANON | ECHO | ECHOE | ECHOK | IEXTEN;
    modes.c_cc[VINTR] = 3;
    modes.c_cc[VEOF] = 4;
    modes.c_cc[VERASE] = 127;
    if (tcsetattr(slave, TCSANOW, &modes) || ioctl(slave, TIOCSWINSZ, &size)) {
        error = errno; ::close(slave); fail(env, "Initialize PTY", error); return 0;
    }
    int errors[2];
    if (pipe2(errors, O_CLOEXEC) < 0) { error = errno; ::close(slave); fail(env, "Launch pipe", error); return 0; }
    pty->wake = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (pty->wake < 0) {
        error = errno; ::close(slave); ::close(errors[0]); ::close(errors[1]);
        fail(env, "PTY wake descriptor", error); return 0;
    }
    struct rlimit limit{};
    if (getrlimit(RLIMIT_NOFILE, &limit) < 0) {
        error = errno; ::close(slave); ::close(errors[0]); ::close(errors[1]);
        fail(env, "Descriptor limit", error); return 0;
    }
    const pid_t parent = getpid();
    const pid_t child = fork();
    if (child == 0) {
        ::close(errors[0]);
        if (prctl(PR_SET_PDEATHSIG, SIGTERM) < 0 || getppid() != parent) childFailure(errors[1]);
        sigset_t empty;
        sigemptyset(&empty);
        sigprocmask(SIG_SETMASK, &empty, nullptr);
        struct sigaction normal{};
        normal.sa_handler = SIG_DFL;
        sigemptyset(&normal.sa_mask);
        for (int signal = 1; signal < NSIG; ++signal) sigaction(signal, &normal, nullptr);
        if (setsid() < 0 || ioctl(slave, TIOCSCTTY, 0) < 0) childFailure(errors[1]);
        for (int fd = 0; fd < 3; ++fd) if (dup2(slave, fd) < 0) childFailure(errors[1]);
        for (rlim_t fd = 3; fd < limit.rlim_cur; ++fd) {
            if (fd != static_cast<rlim_t>(errors[1])) ::close(static_cast<int>(fd));
        }
        if (chdir(cwd.c_str()) < 0) childFailure(errors[1]);
        execve(argv[0], argv.data(), environ.data());
        childFailure(errors[1]);
    }
    error = errno;
    ::close(slave);
    ::close(errors[1]);
    if (child < 0) { ::close(errors[0]); fail(env, "Fork local terminal", error); return 0; }
    pty->child = child;
    pollfd launch{errors[0], POLLIN, 0};
    int ready;
    do { ready = poll(&launch, 1, 5000); } while (ready < 0 && errno == EINTR);
    int childError = 0;
    const ssize_t count = ready > 0 ? ::read(errors[0], &childError, sizeof(childError)) : -1;
    ::close(errors[0]);
    if (count != 0) {
        pty->signalChild(SIGKILL);
        fail(env, "Execute local terminal", count > 0 ? childError : ETIMEDOUT); return 0;
    }
    std::lock_guard<std::mutex> guard(handlesLock);
    const jlong handle = nextHandle++;
    handles.emplace(handle, std::move(pty));
    return handle;
}

extern "C" JNIEXPORT jint JNICALL JNI_METHOD(read)(JNIEnv *env, jobject, jlong handle, jbyteArray buffer) {
    auto pty = lookup(handle);
    if (!pty) return -1;
    pollfd fds[] = {{pty->master, POLLIN, 0}, {pty->wake, POLLIN, 0}};
    int ready;
    do { ready = poll(fds, 2, 100); } while (ready < 0 && errno == EINTR);
    if (ready < 0) { fail(env, "Read local terminal"); return -1; }
    if (fds[1].revents) return -1;
    if (!ready) return 0;
    const jsize capacity = env->GetArrayLength(buffer);
    if (capacity < 1) { fail(env, "Empty PTY read buffer", EINVAL); return -1; }
    std::vector<jbyte> bytes(static_cast<size_t>(capacity > 32768 ? 32768 : capacity));
    ssize_t count;
    do { count = ::read(pty->master, bytes.data(), bytes.size()); } while (count < 0 && errno == EINTR);
    if (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return 0;
    if (count == 0 || (count < 0 && errno == EIO)) return -1;
    if (count < 0) { fail(env, "Read local terminal"); return -1; }
    env->SetByteArrayRegion(buffer, 0, static_cast<jsize>(count), bytes.data());
    return static_cast<jint>(count);
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(write)(JNIEnv *env, jobject, jlong handle, jbyteArray buffer) {
    auto pty = lookup(handle);
    if (!pty) { fail(env, "Local terminal closed", EPIPE); return; }
    const jsize length = env->GetArrayLength(buffer);
    if (length > 65536) { fail(env, "PTY write too large", EINVAL); return; }
    std::vector<jbyte> bytes(static_cast<size_t>(length));
    env->GetByteArrayRegion(buffer, 0, length, bytes.data());
    if (env->ExceptionCheck()) return;
    std::lock_guard<std::mutex> writer(pty->writer);
    size_t offset = 0;
    const auto deadline = Clock::now() + std::chrono::seconds(5);
    while (offset < bytes.size()) {
        pollfd fds[] = {{pty->master, POLLOUT, 0}, {pty->wake, POLLIN, 0}};
        int result = poll(fds, 2, 100);
        if (result < 0 && errno == EINTR) continue;
        if (result < 0 || fds[1].revents || Clock::now() >= deadline) {
            fail(env, "Write local terminal", fds[1].revents ? EPIPE : ETIMEDOUT); return;
        }
        if (!result) continue;
        ssize_t count = ::write(pty->master, bytes.data() + offset, bytes.size() - offset);
        if (count < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        if (count <= 0) { fail(env, "Write local terminal", count == 0 ? EIO : errno); return; }
        offset += static_cast<size_t>(count);
    }
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(resize)(JNIEnv *env, jobject, jlong handle, jint columns, jint rows) {
    auto pty = lookup(handle);
    if (!pty) return;
    if (columns < 1 || rows < 1 || columns > 500 || rows > 500) { fail(env, "PTY size", EINVAL); return; }
    std::lock_guard<std::mutex> guard(pty->state);
    if (pty->closed) return;
    struct winsize size{};
    size.ws_col = static_cast<unsigned short>(columns);
    size.ws_row = static_cast<unsigned short>(rows);
    if (ioctl(pty->master, TIOCSWINSZ, &size) < 0) fail(env, "Resize local terminal");
}

extern "C" JNIEXPORT jint JNICALL JNI_METHOD(exitCode)(JNIEnv *, jobject, jlong handle) {
    auto pty = lookup(handle);
    if (!pty) return -1;
    std::lock_guard<std::mutex> guard(pty->state);
    return pty->reap();
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(close)(JNIEnv *, jobject, jlong handle) {
    std::shared_ptr<Pty> pty;
    {
        std::lock_guard<std::mutex> guard(handlesLock);
        auto found = handles.find(handle);
        if (found == handles.end()) return;
        pty = std::move(found->second);
        handles.erase(found);
    }
    {
        std::lock_guard<std::mutex> guard(pty->state);
        pty->closed = true;
        uint64_t wake = 1;
        const ssize_t ignored = ::write(pty->wake, &wake, sizeof(wake));
        (void) ignored;
    }
    pty->signalChild(SIGTERM); // PRoot can terminate its tracees before it exits.
    for (int attempt = 0; attempt < 50; ++attempt) {
        {
            std::lock_guard<std::mutex> guard(pty->state);
            if (pty->reap() >= 0) return;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    pty->signalChild(SIGKILL);
}
