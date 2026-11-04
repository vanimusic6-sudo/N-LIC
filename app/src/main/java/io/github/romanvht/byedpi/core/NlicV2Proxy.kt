package io.github.romanvht.byedpi.core

/**
 * Independent experimental N-LIC V2 native proxy.
 *
 * This is NOT a drop-in substitute for Xray/ByeDPI: Android must exclude
 * its own UID from the TUN before using its upstream sockets. The Java
 * wrapper exposes only an authenticated localhost SOCKS endpoint, without
 * accepting arbitrary network bind addresses or external command lines.
 */
internal class NlicV2Proxy {
    companion object {
        init { System.loadLibrary("nlicv2") }
    }

    fun run(port: Int, username: String, password: String, splitTls: Boolean): Int =
        nativeRun(port, username, password, splitTls)

    fun stop() = nativeStop()

    private external fun nativeRun(
        port: Int, username: String, password: String, splitTls: Boolean
    ): Int
    private external fun nativeStop()
}
