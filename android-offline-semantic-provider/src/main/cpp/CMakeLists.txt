cmake_minimum_required(VERSION 3.31.6)

project(liliya_offline_semantic_provider LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT ANDROID)
    message(FATAL_ERROR "Liliya offline semantic provider is Android-only")
endif()

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Offline Semantic Provider v0.1 supports only arm64-v8a")
endif()

include(FetchContent)

# The original generation-runtime checkpoint (0f3a71be...) is intentionally
# retained by :android-llama-cpp-engine. The semantic provider uses a separate,
# explicitly audited runtime because its BERT/embedding graph and GGUF metadata
# compatibility are independent from the causal generation path.
# llama.cpp release b10516, published 2026-08-20.
set(LILIYA_LLAMA_CPP_COMMIT "b95502ba9aa0eb73a2f4fc8878d7fbe6a847a0b9")

FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG ${LILIYA_LLAMA_CPP_COMMIT}
    GIT_SHALLOW FALSE
    GIT_PROGRESS TRUE
)

FetchContent_MakeAvailable(llama_cpp)

add_library(
    liliya-semantic-jni
    SHARED
    liliya_semantic_jni.cpp
)

target_include_directories(
    liliya-semantic-jni
    PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
)

target_link_libraries(
    liliya-semantic-jni
    PRIVATE
    llama
    android
    log
)
