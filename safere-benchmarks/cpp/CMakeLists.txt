# Copyright (c) 2025 Eddie Aftandilian. Licensed under the MIT License.
# See LICENSE file in the project root for details.

cmake_minimum_required(VERSION 3.14)
project(re2_benchmark CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Fetch dependencies.
include(FetchContent)

# Disable install targets for dependencies (we only need to build them).
set(ABSL_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
set(RE2_BUILD_TESTING OFF CACHE BOOL "" FORCE)

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

# JSON parser (header-only).
FetchContent_Declare(
  json
  GIT_REPOSITORY https://github.com/nlohmann/json.git
  GIT_TAG        v3.11.3
)

# Suppress install(EXPORT) errors from RE2.
set(CMAKE_SKIP_INSTALL_RULES ON)

FetchContent_MakeAvailable(abseil-cpp re2 json)

add_executable(re2_benchmark re2_benchmark.cc)
target_link_libraries(re2_benchmark re2::re2 nlohmann_json::nlohmann_json)
