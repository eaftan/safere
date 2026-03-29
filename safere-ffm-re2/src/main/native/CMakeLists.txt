cmake_minimum_required(VERSION 3.22)
project(re2_shim C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# RE2 source is at safere-ffm-re2/third_party/re2/ relative to repo root.
# This CMakeLists.txt is at safere-ffm-re2/src/main/native/.
get_filename_component(MODULE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../.." ABSOLUTE)
set(RE2_SOURCE_DIR "${MODULE_ROOT}/third_party/re2")

# Build RE2 from vendored source as a static library
set(RE2_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory(${RE2_SOURCE_DIR} ${CMAKE_CURRENT_BINARY_DIR}/re2
                 EXCLUDE_FROM_ALL)

# Build our C shim as a shared library, statically linking RE2 into it
add_library(re2_shim SHARED re2_shim.cpp)
target_include_directories(re2_shim PRIVATE ${RE2_SOURCE_DIR})
target_link_libraries(re2_shim PRIVATE re2)
