cmake_minimum_required(VERSION 3.22.1)
project("testmatriz")

add_library(matrixbench SHARED matrixbench.c)
find_library(log-lib log)
target_link_libraries(matrixbench
        android
        log
        ${log-lib})
