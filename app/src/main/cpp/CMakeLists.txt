cmake_minimum_required(VERSION 3.22.1)
project(bitchord_analysis LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(ANALYZER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/analyzer)
add_library(bitchord_analysis SHARED
    ${ANALYZER_DIR}/audio_analysis.cpp
    ${ANALYZER_DIR}/tempo_analysis.cpp
    ${ANALYZER_DIR}/resampler.cpp
    ${ANALYZER_DIR}/mel_spectrogram.cpp
    ${ANALYZER_DIR}/vocal_spectrogram.cpp
    jni/analysis_jni.cpp
    jni/mel_jni.cpp
    jni/vocal_jni.cpp
)
target_include_directories(bitchord_analysis PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${ANALYZER_DIR})
target_compile_options(bitchord_analysis PRIVATE -O3 -fvisibility=hidden)
find_library(log-lib log)
target_link_libraries(bitchord_analysis ${log-lib})
