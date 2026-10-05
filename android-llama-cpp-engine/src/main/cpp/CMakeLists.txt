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

# Native-source caching is a speed optimization only. Any already-present source must be the exact
# reviewed commit and must not contain tracked modifications; otherwise fail closed before CMake
# evaluates upstream source.
find_program(LILIYA_GIT_EXECUTABLE git REQUIRED)

function(liliya_validate_llama_source source_dir source_label)
    if(NOT EXISTS "${source_dir}/.git")
        message(FATAL_ERROR "${source_label} is not a git checkout")
    endif()

    execute_process(
        COMMAND "${LILIYA_GIT_EXECUTABLE}" -C "${source_dir}" rev-parse HEAD
        RESULT_VARIABLE LILIYA_LLAMA_GIT_RESULT
        OUTPUT_VARIABLE LILIYA_LLAMA_GIT_HEAD
        OUTPUT_STRIP_TRAILING_WHITESPACE
        ERROR_QUIET
    )
    if(NOT LILIYA_LLAMA_GIT_RESULT EQUAL 0 OR
       NOT LILIYA_LLAMA_GIT_HEAD STREQUAL LILIYA_LLAMA_CPP_COMMIT)
        message(FATAL_ERROR
            "${source_label} is not the reviewed commit ${LILIYA_LLAMA_CPP_COMMIT}"
        )
    endif()

    execute_process(
        COMMAND "${LILIYA_GIT_EXECUTABLE}" -C "${source_dir}"
                status --porcelain --untracked-files=no
        RESULT_VARIABLE LILIYA_LLAMA_STATUS_RESULT
        OUTPUT_VARIABLE LILIYA_LLAMA_TRACKED_STATUS
        OUTPUT_STRIP_TRAILING_WHITESPACE
        ERROR_QUIET
    )
    if(NOT LILIYA_LLAMA_STATUS_RESULT EQUAL 0 OR
       NOT LILIYA_LLAMA_TRACKED_STATUS STREQUAL "")
        message(FATAL_ERROR "${source_label} has tracked modifications")
    endif()
endfunction()

if(DEFINED FETCHCONTENT_SOURCE_DIR_LLAMA_CPP AND
   NOT "${FETCHCONTENT_SOURCE_DIR_LLAMA_CPP}" STREQUAL "")
    liliya_validate_llama_source(
        "${FETCHCONTENT_SOURCE_DIR_LLAMA_CPP}"
        "Local llama.cpp override"
    )
elseif(DEFINED FETCHCONTENT_BASE_DIR AND
       EXISTS "${FETCHCONTENT_BASE_DIR}/llama_cpp-src/.git")
    liliya_validate_llama_source(
        "${FETCHCONTENT_BASE_DIR}/llama_cpp-src"
        "Cached llama.cpp source"
    )
endif()

FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG ${LILIYA_LLAMA_CPP_COMMIT}
    GIT_SHALLOW FALSE
    GIT_PROGRESS TRUE
)

FetchContent_MakeAvailable(llama_cpp)

# Validate a freshly populated source too. Existing caches are validated above before upstream
# CMake is evaluated; this second check closes the first-population path.
liliya_validate_llama_source("${llama_cpp_SOURCE_DIR}" "Resolved llama.cpp source")

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
