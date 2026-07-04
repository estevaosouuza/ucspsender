cmake_minimum_required(VERSION 3.28...3.30)

include("${CMAKE_CURRENT_SOURCE_DIR}/cmake/common/bootstrap.cmake" NO_POLICY_SCOPE)

project(${_name} VERSION ${_version})

option(ENABLE_FRONTEND_API "Use obs-frontend-api for UI functionality" OFF)
option(ENABLE_QT "Use Qt functionality" OFF)

include(compilerconfig)
include(defaults)
include(helpers)

add_library(${CMAKE_PROJECT_NAME} MODULE)

find_package(libobs REQUIRED)
target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE OBS::libobs)

if(ENABLE_FRONTEND_API)
  find_package(obs-frontend-api REQUIRED)
  target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE OBS::obs-frontend-api)
endif()

if(ENABLE_QT)
  find_package(Qt6 COMPONENTS Widgets Core)
  target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE Qt6::Core Qt6::Widgets)
  target_compile_options(
    ${CMAKE_PROJECT_NAME}
    PRIVATE $<$<C_COMPILER_ID:Clang,AppleClang>:-Wno-quoted-include-in-framework-header -Wno-comma>
  )
  set_target_properties(
    ${CMAKE_PROJECT_NAME}
    PROPERTIES AUTOMOC ON AUTOUIC ON AUTORCC ON
  )
endif()

target_sources(
  ${CMAKE_PROJECT_NAME}
  PRIVATE
    src/plugin-main.cpp
    src/ucsp-source.cpp
    src/ucsp-source.h
    src/ucsp/ucsp_protocol.h
    src/ucsp/ucsp_protocol.cpp
    src/ucsp/udp_receiver.h
    src/ucsp/udp_receiver.cpp
    src/ucsp/sender_registry.h
    src/ucsp/sender_registry.cpp
    src/ucsp/fec_decoder.h
    src/ucsp/fec_decoder.cpp
    src/ucsp/frame_reassembler.h
    src/ucsp/frame_reassembler.cpp
    src/ucsp/h264_decoder.h
    src/ucsp/h264_decoder.cpp
    src/ucsp/backchannel_sender.h
    src/ucsp/backchannel_sender.cpp
)

if(OS_WINDOWS)
  # FFmpeg (avcodec/avformat/avutil/swscale) via vcpkg classic mode -- linked directly by
  # path rather than through vcpkg's CMAKE_TOOLCHAIN_FILE, since that toolchain's global
  # find_package() override conflicts with this template's own libobs discovery above.
  if(NOT DEFINED ENV{VCPKG_ROOT})
    message(FATAL_ERROR "VCPKG_ROOT environment variable is not set (see docs/roadmap.md Phase 0)")
  endif()
  set(UCSP_VCPKG_DIR "$ENV{VCPKG_ROOT}/installed/x64-windows")

  target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE "${UCSP_VCPKG_DIR}/include")
  target_link_libraries(
    ${CMAKE_PROJECT_NAME}
    PRIVATE
      "${UCSP_VCPKG_DIR}/lib/avcodec.lib"
      "${UCSP_VCPKG_DIR}/lib/avformat.lib"
      "${UCSP_VCPKG_DIR}/lib/avutil.lib"
      "${UCSP_VCPKG_DIR}/lib/swscale.lib"
      ws2_32
  )
endif()

set_target_properties_plugin(${CMAKE_PROJECT_NAME} PROPERTIES OUTPUT_NAME ${_name})
