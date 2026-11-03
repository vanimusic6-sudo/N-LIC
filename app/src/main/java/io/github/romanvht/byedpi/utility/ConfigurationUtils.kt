package io.github.romanvht.byedpi.utility

import android.content.Context
import io.github.romanvht.byedpi.core.ByeDpiProxyCmdPreferences
import io.github.romanvht.byedpi.data.Configuration

fun testConfiguration(context: Context, command: String, host: String, port: Int): Configuration {
    val args = ByeDpiProxyCmdPreferences(context, command, host, port).args.toList()
    val (commandHost, commandPort) = args.checkIpAndPortInArgs()
    val effectivePort = commandPort?.toIntOrNull() ?: port
    require(effectivePort in 1..65535) { "Invalid test proxy port" }
    return Configuration(args, commandHost ?: host, effectivePort)
}
