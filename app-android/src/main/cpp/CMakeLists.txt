cmake_minimum_required(VERSION 3.22.1)

project(liteagent_llm)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(liteagent_llm SHARED llm_jni.cpp)

target_include_directories(liteagent_llm PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/include/llm
)

# libMNN.so 由 tools/build_mnn_android.sh 产出，放在 jniLibs 下。
# 注意：它用 -DANDROID_STL=c++_static 编译，本模块也必须用 c++_static，
# 否则跨库传递 std::string / std::vector 会出现两套 STL 实例，运行时崩溃。
add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES IMPORTED_LOCATION
        ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libMNN.so)

target_link_libraries(liteagent_llm
        MNN
        android
        log
)

# Android 15+ 要求 16KB page size 对齐
target_link_options(liteagent_llm PRIVATE "-Wl,-z,max-page-size=16384")
