cmake_minimum_required(VERSION 3.22)
project(localdoc_jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(CORE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../cpp_core")
set(LLAMA_DIR "${CORE_DIR}/llama.cpp")
set(BRIDGE_DIR "${CORE_DIR}/bridge")

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Ядро llama.cpp + единый C-мост
add_subdirectory("${LLAMA_DIR}" llama_build)

add_library(localdoc_core STATIC "${BRIDGE_DIR}/localdoc_c_api.cpp")
target_include_directories(localdoc_core PUBLIC "${BRIDGE_DIR}")
target_link_libraries(localdoc_core PUBLIC llama)

# Тонкий JNI-мост
add_library(localdoc_jni SHARED android_jni.cpp)
target_include_directories(localdoc_jni PRIVATE "${BRIDGE_DIR}")
target_link_libraries(localdoc_jni PRIVATE localdoc_core log)
