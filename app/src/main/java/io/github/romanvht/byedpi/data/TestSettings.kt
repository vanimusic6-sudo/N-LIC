package io.github.romanvht.byedpi.data

import io.github.romanvht.byedpi.utility.AccessType

data class TestSettings(
    val sites: List<String>,
    val commands: List<String>,
    val host: String,
    val port: Int,
    val delaySec: Int,
    val requestsCount: Int,
    val requestTimeout: Long,
    val requestLimit: Int,
    val mediaTestUrl: String? = null,
    val networkType: AccessType = AccessType.UNKNOWN,
)
