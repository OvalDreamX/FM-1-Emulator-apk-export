cmake_minimum_required(VERSION 3.22.1)
project(fm1_android_app LANGUAGES CXX)

add_library(fm1_android_native SHARED IMPORTED GLOBAL)
set_target_properties(fm1_android_native PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_LIST_DIR}/../jniLibs/${ANDROID_ABI}/libfm1_android_native.so")

add_library(fm1_android_app SHARED AudioOutput.cpp)
target_compile_features(fm1_android_app PRIVATE cxx_std_17)
target_compile_options(fm1_android_app PRIVATE -O3 -Wall -Wextra)

find_library(android-lib android)
find_library(aaudio-lib aaudio)

target_link_libraries(fm1_android_app
    fm1_android_native
    ${aaudio-lib}
    ${android-lib})
