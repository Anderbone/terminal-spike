cmake_minimum_required(VERSION 3.22)
project(terminal_spike_mosh_extension LANGUAGES C CXX)

foreach(required MOSH_SOURCE_DIR NETTLE_INCLUDE_DIR NETTLE_LIBRARY PROTOBUF_INCLUDE_DIR PROTOBUF_LITE_LIBRARY)
    if(NOT DEFINED ${required})
        message(FATAL_ERROR "${required} is required")
    endif()
endforeach()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(MOSH_TERMINAL_SOURCES
    ${MOSH_SOURCE_DIR}/src/terminal/parser.cc
    ${MOSH_SOURCE_DIR}/src/terminal/parseraction.cc
    ${MOSH_SOURCE_DIR}/src/terminal/parserstate.cc
    ${MOSH_SOURCE_DIR}/src/terminal/terminal.cc
    ${MOSH_SOURCE_DIR}/src/terminal/terminaldispatcher.cc
    ${MOSH_SOURCE_DIR}/src/terminal/terminaldisplay.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/port/terminaldisplayinit_android.cc
    ${MOSH_SOURCE_DIR}/src/terminal/terminalframebuffer.cc
    ${MOSH_SOURCE_DIR}/src/terminal/terminalfunctions.cc
    ${MOSH_SOURCE_DIR}/src/terminal/terminaluserinput.cc)

add_library(mosh_extension SHARED
    mosh_native.cpp
    ${MOSH_SOURCE_DIR}/src/crypto/base64.cc
    ${MOSH_SOURCE_DIR}/src/crypto/crypto.cc
    ${MOSH_SOURCE_DIR}/src/crypto/ocb_internal.cc
    ${MOSH_SOURCE_DIR}/src/frontend/terminaloverlay.cc
    ${MOSH_SOURCE_DIR}/src/network/compressor.cc
    ${MOSH_SOURCE_DIR}/src/network/network.cc
    ${MOSH_SOURCE_DIR}/src/network/transportfragment.cc
    ${MOSH_SOURCE_DIR}/src/statesync/completeterminal.cc
    ${MOSH_SOURCE_DIR}/src/statesync/user.cc
    ${MOSH_TERMINAL_SOURCES}
    ${MOSH_SOURCE_DIR}/src/util/timestamp.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/generated/hostinput.pb.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/generated/transportinstruction.pb.cc
    ${CMAKE_CURRENT_SOURCE_DIR}/generated/userinput.pb.cc)

target_include_directories(mosh_extension PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/port
    ${CMAKE_CURRENT_SOURCE_DIR}/generated
    ${MOSH_SOURCE_DIR}/src/crypto
    ${MOSH_SOURCE_DIR}/src/frontend
    ${MOSH_SOURCE_DIR}/src/network
    ${MOSH_SOURCE_DIR}/src/statesync
    ${MOSH_SOURCE_DIR}/src/terminal
    ${MOSH_SOURCE_DIR}/src/util
    ${NETTLE_INCLUDE_DIR}
    ${PROTOBUF_INCLUDE_DIR})

add_library(nettle STATIC IMPORTED)
set_target_properties(nettle PROPERTIES IMPORTED_LOCATION "${NETTLE_LIBRARY}")
add_library(protobuf_lite STATIC IMPORTED)
set_target_properties(protobuf_lite PROPERTIES IMPORTED_LOCATION "${PROTOBUF_LITE_LIBRARY}")

find_library(z_library z REQUIRED)
find_library(log_library log REQUIRED)
find_library(dl_library dl REQUIRED)
target_link_libraries(
    mosh_extension PRIVATE nettle protobuf_lite ${z_library} ${log_library} ${dl_library})

target_compile_options(mosh_extension PRIVATE
    -fstack-protector-strong
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
    -Wall
    -Wextra
    -Wno-unused-parameter
    -Wno-deprecated-declarations)
target_compile_definitions(mosh_extension PRIVATE _FORTIFY_SOURCE=2)
target_link_options(mosh_extension PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map
    -Wl,-z,relro
    -Wl,-z,now
    -Wl,-z,noexecstack
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384)
