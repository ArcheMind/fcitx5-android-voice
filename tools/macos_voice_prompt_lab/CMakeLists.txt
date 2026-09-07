cmake_minimum_required(VERSION 3.20)
project(macos_voice_prompt_lab LANGUAGES CXX)

set(MNN_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../../third_party/MNN" CACHE PATH
    "Path to the MNN source pinned by fcitx5-android-voice")

if(NOT EXISTS "${MNN_SOURCE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "MNN_SOURCE_DIR does not contain MNN: ${MNN_SOURCE_DIR}")
endif()

set(MNN_BUILD_LLM ON CACHE BOOL "" FORCE)
set(MNN_BUILD_LLM_OMNI ON CACHE BOOL "" FORCE)
set(MNN_LLM_BUILD_DEMO OFF CACHE BOOL "" FORCE)
set(MNN_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(MNN_SEP_BUILD OFF CACHE BOOL "" FORCE)

add_subdirectory("${MNN_SOURCE_DIR}" "${CMAKE_BINARY_DIR}/mnn")

add_executable(macos_voice_prompt_lab main.cpp)
target_compile_features(macos_voice_prompt_lab PRIVATE cxx_std_17)
target_include_directories(macos_voice_prompt_lab PRIVATE
    "${MNN_SOURCE_DIR}/transformers/llm/engine/include"
    "${MNN_SOURCE_DIR}/3rd_party"
)
target_link_libraries(macos_voice_prompt_lab PRIVATE MNN)
