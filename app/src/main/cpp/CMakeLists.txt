# CMakeLists.txt for Voice Changer Native Library
cmake_minimum_required(VERSION 3.22.1)
project("voicechanger")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add the stftPitchShift header-only library
include_directories(${CMAKE_SOURCE_DIR}/stftPitchShift)

# Create the shared library
add_library(voicechanger SHARED
    voicechanger_jni.cpp
    VoiceProcessor.cpp
)

# Find required libraries
find_library(log-lib log)
find_library(android-lib android)

# Link libraries
target_link_libraries(voicechanger
    ${log-lib}
    ${android-lib}
)

# Enable optimizations for release builds
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    target_compile_options(voicechanger PRIVATE -O3 -ffast-math)
endif()
