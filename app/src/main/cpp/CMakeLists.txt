cmake_minimum_required(VERSION 3.10.2)
project("pedigreeapp")

# 指定 C99 标准 (支持 bool、true、false)
set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

# 将 C 代码和 SQLite 源码一起编译
add_library(pedigree_native SHARED
        native-lib.c
        sqlite3.c)

# 忽略 sqlite3.c 的大量编译警告
set_source_files_properties(sqlite3.c PROPERTIES COMPILE_FLAGS "-w")

target_include_directories(pedigree_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(log-lib log)

target_link_libraries(pedigree_native
        ${log-lib}
        m) # 链接数学库