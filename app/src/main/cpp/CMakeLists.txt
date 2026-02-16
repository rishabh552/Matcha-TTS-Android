cmake_minimum_required(VERSION 3.22.1)
project(matcha_tts_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(SHERPA_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/include)
set(PREBUILT_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

foreach(REQUIRED_FILE
    ${SHERPA_INCLUDE_DIR}/sherpa-onnx/c-api/c-api.h
    ${PREBUILT_LIB_DIR}/libsherpa-onnx-c-api.so
    ${PREBUILT_LIB_DIR}/libonnxruntime.so)
    if(NOT EXISTS ${REQUIRED_FILE})
        message(FATAL_ERROR "Missing required file: ${REQUIRED_FILE}")
    endif()
endforeach()

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION ${PREBUILT_LIB_DIR}/libonnxruntime.so)

add_library(sherpa-onnx-c-api SHARED IMPORTED)
set_target_properties(sherpa-onnx-c-api PROPERTIES
    IMPORTED_LOCATION ${PREBUILT_LIB_DIR}/libsherpa-onnx-c-api.so)

add_library(native-lib SHARED native-lib.cpp)

target_include_directories(native-lib PRIVATE ${SHERPA_INCLUDE_DIR})

target_link_libraries(native-lib
    PRIVATE
    sherpa-onnx-c-api
    onnxruntime
    android
    log
)
