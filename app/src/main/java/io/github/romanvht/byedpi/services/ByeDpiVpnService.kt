package io.github.romanvht.byedpi.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.activities.MainActivity
import io.github.romanvht.byedpi.data.*
import io.github.romanvht.byedpi.receiver.ServiceActionReceiver
import io.github.romanvht.byedpi.utility.*
import io.github.romanvht.byedpi.subscription.NativeTunConfig

@SuppressLint("VpnServicePolicy")
class ByeDpiVpnService : VpnService() {
    companion object {
        internal const val FOREGROUND_SERVICE_ID = 1
        private const val PAUSE_NOTIFICATION_ID = 3
        private const val NOTIFICATION_CHANNEL_ID = "ByeDPIVpn"
        private const val TAG = "ByeDpiVpnService"
    }

    private val session by lazy { NativeSession(this, ::createTunnel) }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel(this, NOTIFICATION_CHANNEL_ID, R.string.vpn_channel_name)
    }

    override fun onDestroy() {
        super.onDestroy()
        session.destroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        val systemBinder = super.onBind(intent)
        return when (intent.action) {
            SERVICE_INTERFACE -> systemBinder
            EngineProtocol.CONTROL_ACTION -> session.binder
            else -> null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            START_ACTION -> {
                startForeground()
                session.onStarted()
            }
            null, SERVICE_INTERFACE, RESUME_ACTION -> {
                startForeground()
                session.onStarted()
                sendControl(START_ACTION)
            }
            STOP_ACTION -> {
                sendControl(STOP_ACTION)
                session.stop()
            }
            PAUSE_ACTION -> {
                sendControl(PAUSE_ACTION)
                session.stop()
            }
            else -> session.stop()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        sendControl(STOP_ACTION)
        session.stop()
    }

    private fun startForeground() {
        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notifications.cancel(PAUSE_NOTIFICATION_ID)
        val notification: Notification = createConnectionNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            R.string.vpn_notification_content,
            Mode.VPN,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FOREGROUND_SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }
    }

    private fun createTunnel(configuration: Configuration, underlyingLink: AccessSnapshot, mtu: Int): ParcelFileDescriptor {
        val builder = Builder()
        builder.setSession("N-LIC")
        val coreActive = configuration.args.firstOrNull() == "--nlic-libbox"
        val xrayActive = configuration.args.firstOrNull() == "--nlic-xray"
        val v2Active = configuration.args.firstOrNull() == "--nlic-v2"
        // V2's bounded UDP relay cannot safely accept the older 8.5k
        // synthetic interface MTU on Wi-Fi. Both Builder and HEV must
        // receive the same 1280-1400 MTU, including on IPv6-only links.
        if (coreActive || xrayActive || v2Active ||
            (underlyingLink.isCellular && mtu < 8500)) {
            builder.setMtu(mtu)
        }
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )

        // The Android addresses MUST match the native sing-box TUN config.
        // A mismatch here causes native handshake success but no app traffic.
        builder.addAddress(
            if (coreActive) NativeTunConfig.IPV4_ADDRESS else "10.10.10.10",
            if (coreActive) NativeTunConfig.IPV4_PREFIX else 32,
        ).addRoute("0.0.0.0", 0)

        val mobileIpv6 = getPreferences().getBoolean("nlic_mobile_ipv6_auto", true)
        // An encrypted LIC-Core session must never accidentally let IPv6
        // escape when the mobile link advertises a dual-stack network.
        // Legacy ByeDPI keeps its existing per-carrier IPv6 policy.
        if (coreActive ||
            AccessPolicy.shouldCaptureIpv6(configuration.ipv6, underlyingLink, mobileIpv6)) {
            builder.addAddress(
                if (coreActive) NativeTunConfig.IPV6_ADDRESS else "fd00::1",
                if (coreActive) NativeTunConfig.IPV6_PREFIX else 128
            )
                .addRoute("::", 0)
        }

        if (coreActive) {
            // sing-box 1.14 native TUN hijacks port 53 on this synthetic IP;
            // visited domain names do not go to the carrier DNS resolver.
            builder.addDnsServer(NativeTunConfig.IPV4_DNS)
        } else if (configuration.dns.isNotBlank()) {
            builder.addDnsServer(configuration.dns)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Respect mobile carrier metering; do not falsely advertise unmetered data.
            if (underlyingLink.type != AccessType.UNKNOWN) {
                builder.setMetered(underlyingLink.isCellular)
            }
        }

        when (configuration.appListType) {
            "blacklist" -> {
                for (packageName in configuration.apps) {
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (_: Exception) {
                        // Avoid printing package names or exception text to
                        // logcat. A rejected blacklist entry stays routed
                        // through VPN, rather than leaking to the carrier.
                        Log.w(TAG, "Ignored invalid VPN exclusion")
                    }
                }

                builder.addDisallowedApplication(applicationContext.packageName)
            }

            "whitelist" -> {
                var allowed = 0
                for (packageName in configuration.apps) {
                    // The N-LIC UID must never enter its own TUN: its
                    // native SOCKS outbound sockets would recursively loop.
                    if (packageName == applicationContext.packageName) continue
                    try {
                        builder.addAllowedApplication(packageName)
                        allowed++
                    } catch (_: Exception) {
                        // No package identifiers or system exception text in
                        // diagnostic logs; a failed allow-list rule must not
                        // be reported as successfully routed.
                        Log.w(TAG, "Ignored invalid VPN inclusion")
                    }
                }
                check(allowed > 0) { "Select at least one installed app for VPN whitelist" }
            }

            "disable" -> {
                builder.addDisallowedApplication(applicationContext.packageName)
            }
            else -> error("Unsupported application routing mode")
        }

        return builder.establish() ?: throw IllegalStateException("VPN connection failed")
    }

    private fun sendControl(action: String) {
        sendBroadcast(Intent(this, ServiceActionReceiver::class.java)
            .setAction(action)
            .putExtra("pid", Process.myPid())
            .putExtra(ServiceActionReceiver.EXTRA_MODE, Mode.VPN.name))
    }
}
