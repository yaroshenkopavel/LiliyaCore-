cmake_minimum_required(VERSION 3.31.6)

project(liliya_llama_cpp_engine LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT ANDROID)
    message(FATAL_ERROR "Liliya llama.cpp engine is Android-only")
endif()

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Slice 7 supports only arm64-v8a")
endif()

include(FetchContent)

# Reviewed upstream checkpoint. Never replace this with a floating branch or tag.
set(LILIYA_LLAMA_CPP_COMMIT "0f3a71be15af836d277c9f918adfafb45732677e")

FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG ${LILIYA_LLAMA_CPP_COMMIT}
    GIT_SHALLOW FALSE
    GIT_PROGRESS TRUE
)

FetchContent_MakeAvailable(llama_cpp)

add_library(
    liliya-llama-jni
    SHARED
    liliya_llama_jni.cpp
)

target_include_directories(
    liliya-llama-jni
    PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
)

target_link_libraries(
    liliya-llama-jni
    PRIVATE
    llama
    android
    log
)
