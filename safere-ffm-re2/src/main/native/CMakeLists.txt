cmake_minimum_required(VERSION 3.22)
project(re2_shim C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Fetch dependencies (same versions as safere-benchmarks/cpp/).
include(FetchContent)

set(ABSL_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
set(ABSL_PROPAGATE_CXX_STD ON CACHE BOOL "" FORCE)
set(RE2_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
  abseil-cpp
  GIT_REPOSITORY https://github.com/abseil/abseil-cpp.git
  GIT_TAG        20250814.2
)

FetchContent_Declare(
  re2
  GIT_REPOSITORY https://github.com/google/re2.git
  GIT_TAG        2025-11-05
)

# Suppress install(EXPORT) errors from RE2.
set(CMAKE_SKIP_INSTALL_RULES ON)

FetchContent_MakeAvailable(abseil-cpp re2)

# Build our C shim as a shared library, statically linking RE2 into it
add_library(re2_shim SHARED re2_shim.cpp)
target_link_libraries(re2_shim PRIVATE re2::re2)
