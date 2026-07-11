# libxnotests: the vendored tree-sitter runtime, the bundled grammars and a thin
# JNI bridge, compiled as one small shared library. Sources are vendored at
# pinned tags (see THIRD_PARTY); nothing is fetched at build time, keeping the
# F-Droid build reproducible.
cmake_minimum_required(VERSION 3.22.1)
project(xnotests C)

set(CMAKE_C_STANDARD 11)
# Applied to every build type (AGP release uses RelWithDebInfo, not Release).
add_compile_options(-Os -fvisibility=hidden -ffunction-sections -fdata-sections)

set(GRAMMAR_SOURCES
    grammars/bash/parser.c
    grammars/bash/scanner.c
    grammars/c/parser.c
    grammars/cpp/parser.c
    grammars/cpp/scanner.c
    grammars/java/parser.c
    grammars/javascript/parser.c
    grammars/javascript/scanner.c
    grammars/json/parser.c
    grammars/kotlin/parser.c
    grammars/kotlin/scanner.c
    grammars/python/parser.c
    grammars/python/scanner.c
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

# No build-id note: it hashes the pre-strip output (build paths included), so it
# varies across build hosts and breaks F-Droid's reproducible-build verification.
# max-page-size=16384 keeps the lib loadable on 16 KB page-size devices.
target_link_options(xnotests PRIVATE -Wl,--gc-sections -Wl,--build-id=none
    -Wl,-z,max-page-size=16384)
