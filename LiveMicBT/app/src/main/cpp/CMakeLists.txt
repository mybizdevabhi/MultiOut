cmake_minimum_required(VERSION 3.22.1)
project("livemic_engine" LANGUAGES CXX)

find_package(oboe REQUIRED CONFIG)

add_library(
    livemic_engine
    SHARED
    AudioEngine.cpp
    jni_bridge.cpp
)

target_include_directories(livemic_engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(
    livemic_engine
    PRIVATE
    oboe::oboe
    android
    log
)
