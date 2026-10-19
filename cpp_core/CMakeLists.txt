cmake_minimum_required(VERSION 3.22)
project(localdoc_core LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp подключён как git submodule
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(localdoc_core STATIC bridge/localdoc_c_api.cpp)
target_include_directories(localdoc_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/bridge)
target_link_libraries(localdoc_core PUBLIC llama)
