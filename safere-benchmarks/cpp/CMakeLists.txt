# Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.

cmake_minimum_required(VERSION 3.15)
project(native_regex_benchmarks C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Detect optional platform-specific heap accounting support.
include(CheckCXXSymbolExists)
check_cxx_symbol_exists(mallinfo2 "malloc.h" SAFERE_HAVE_MALLINFO2)

# Fetch dependencies.
include(FetchContent)

# Disable install targets for dependencies (we only need to build them).
set(ABSL_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
set(RE2_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(PCRE2_BUILD_PCRE2_8 ON CACHE BOOL "" FORCE)
set(PCRE2_BUILD_PCRE2_16 OFF CACHE BOOL "" FORCE)
set(PCRE2_BUILD_PCRE2_32 OFF CACHE BOOL "" FORCE)
set(PCRE2_BUILD_PCRE2GREP OFF CACHE BOOL "" FORCE)
set(PCRE2_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(PCRE2_SUPPORT_JIT ON CACHE BOOL "" FORCE)

FetchContent_Declare(
  re2
  GIT_REPOSITORY https://github.com/google/re2.git
  GIT_TAG        2025-11-05
)

# RE2 requires Abseil.
FetchContent_Declare(
  abseil-cpp
  GIT_REPOSITORY https://github.com/abseil/abseil-cpp.git
  GIT_TAG        20250814.2
)

FetchContent_Declare(
  pcre2
  GIT_REPOSITORY https://github.com/PCRE2Project/pcre2.git
  GIT_TAG        pcre2-10.47
)

# JSON parser (header-only).
FetchContent_Declare(
  json
  GIT_REPOSITORY https://github.com/nlohmann/json.git
  GIT_TAG        v3.11.3
)

# Suppress install(EXPORT) errors from RE2.
set(CMAKE_SKIP_INSTALL_RULES ON)

FetchContent_MakeAvailable(abseil-cpp re2 pcre2 json)

add_executable(re2_benchmark native_regex_benchmark.cc)
target_link_libraries(re2_benchmark re2::re2 nlohmann_json::nlohmann_json)

add_executable(pcre2_jit_benchmark native_regex_benchmark.cc)
target_compile_definitions(pcre2_jit_benchmark PRIVATE SAFERE_PCRE2_JIT)
target_link_libraries(
  pcre2_jit_benchmark
  pcre2-8-static
  nlohmann_json::nlohmann_json
)

if(SAFERE_HAVE_MALLINFO2)
  target_compile_definitions(re2_benchmark PRIVATE SAFERE_HAVE_MALLINFO2)
  target_compile_definitions(pcre2_jit_benchmark PRIVATE SAFERE_HAVE_MALLINFO2)
endif()

include(CTest)
if(BUILD_TESTING)
  add_test(NAME benchmark_sha256_test
           COMMAND re2_benchmark --sha256-self-test)
  add_test(NAME re2_benchmark_self_test
           COMMAND re2_benchmark --engine-self-test)
  add_test(NAME pcre2_jit_benchmark_self_test
           COMMAND pcre2_jit_benchmark --engine-self-test)
endif()
