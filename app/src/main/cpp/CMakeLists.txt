cmake_minimum_required(VERSION 3.22.1)

project(byedpi_native)

file(GLOB BYE_DPI_SRC byedpi/*.c)
list(REMOVE_ITEM BYE_DPI_SRC ${CMAKE_CURRENT_SOURCE_DIR}/byedpi/win_service.c)

add_library(byedpi SHARED ${BYE_DPI_SRC} byedpi-jni.c hev-jni.c)
target_include_directories(byedpi PRIVATE byedpi)

target_compile_options(byedpi PRIVATE -std=c99 -O2 -fstack-protector-strong -D_FORTIFY_SOURCE=2 -Wall -Wno-unused -Wextra -Wno-unused-parameter -pedantic)
target_compile_definitions(byedpi PRIVATE ANDROID_APP)

target_link_libraries(byedpi PRIVATE android log dl)
target_link_options(byedpi PRIVATE "-Wl,--wrap=daemon" "-Wl,-z,relro" "-Wl,-z,now")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# Experimental clean-room N-LIC V2 proxy: separate from the proven ByeDPI
# native target, so Xray + LIC-Bay keeps using the stable implementation.
add_library(nlicv2 SHARED
    nlic-v2/nlic_protocol.c
    nlic-v2/nlic_socks.c
    nlic-v2/nlic_udp.c
    nlic-v2/nlic_jni.c)
target_include_directories(nlicv2 PRIVATE nlic-v2)
target_compile_options(nlicv2 PRIVATE
    -std=c11 -O2 -fstack-protector-strong -D_FORTIFY_SOURCE=2
    -Wall -Wextra -Wpedantic)
target_link_libraries(nlicv2 PRIVATE android log)
target_link_options(nlicv2 PRIVATE "-Wl,-z,relro" "-Wl,-z,now"
    "-Wl,-z,max-page-size=16384")
