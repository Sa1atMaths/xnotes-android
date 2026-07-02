# libxnotests: the vendored tree-sitter runtime, the bundled grammars and a thin
# JNI bridge, compiled as one small shared library. Sources are vendored at
# pinned tags (see THIRD_PARTY); nothing is fetched at build time, keeping the
# F-Droid build reproducible.
cmake_minimum_required(VERSION 3.22.1)
project(xnotests C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_FLAGS_RELEASE "-Os -fvisibility=hidden -ffunction-sections -fdata-sections")

set(GRAMMAR_SOURCES
    grammars/json/parser.c
)

add_library(xnotests SHARED
    tree-sitter/lib/src/lib.c
    ${GRAMMAR_SOURCES}
    ts_jni.c
)

target_include_directories(xnotests PRIVATE
    tree-sitter/lib/include
    tree-sitter/lib/src
)

# Each grammar carries its own tree_sitter/parser.h; scope it to that grammar's
# sources so the copies never collide.
foreach(src ${GRAMMAR_SOURCES})
    get_filename_component(dir ${src} DIRECTORY)
    set_source_files_properties(${src} PROPERTIES
        INCLUDE_DIRECTORIES "${CMAKE_CURRENT_SOURCE_DIR}/${dir}")
endforeach()

target_link_options(xnotests PRIVATE -Wl,--gc-sections)
