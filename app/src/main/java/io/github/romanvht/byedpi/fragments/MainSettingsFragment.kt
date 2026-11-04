package io.github.romanvht.byedpi.fragments

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.net.VpnService
import io.github.romanvht.byedpi.services.NativeEngine
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.services.appStatus
import io.github.romanvht.byedpi.data.AppStatus
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.preference.*
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.BuildConfig
import io.github.romanvht.byedpi.activities.TestActivity
import io.github.romanvht.byedpi.activities.SubscriptionLabActivity
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.data.PrivateDnsState
import io.github.romanvht.byedpi.utility.*
import androidx.core.net.toUri

class MainSettingsFragment : PreferenceFragmentCompat() {
    companion object {
        private val TAG: String = MainSettingsFragment::class.java.simpleName
    }

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            updatePreferences()
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.main_settings, rootKey)

        setEditTextPreferenceListener("byedpi_proxy_ip") { checkIp(it) }
        setEditTestPreferenceListenerPort("byedpi_proxy_port")
        setEditTextPreferenceListener("dns_ip") { it.isBlank() || checkNotLocalIp(it) }

        findPreferenceNotNull<ListPreference>("language")
            .setOnPreferenceChangeListener { _, newValue ->
                SettingsUtils.setLang(newValue as String)
                true
            }

        findPreferenceNotNull<ListPreference>("app_theme")
            .setOnPreferenceChangeListener { _, newValue ->
                SettingsUtils.setTheme(newValue as String)
                true
            }

        findPreferenceNotNull<SwitchPreference>(NetworkProfileStore.ENABLED_KEY)
            .setOnPreferenceChangeListener { _, enabled ->
                if (enabled == true) {
                    // When first enabled, remember the current command for
                    // this link type, without touching the other network.
                    val prefs = requireContext().getPreferences()
                    val key = NetworkProfileStore.key(NetworkEnvironment.capture(requireContext()).type)
                    if (key != null && prefs.getString(key, null).isNullOrBlank()) {
                        prefs.edit { putString(key, prefs.getCmdArgs()) }
                    }
                }
                true
            }

        findPreferenceNotNull<Preference>("nlic_subscription_manager")
            .setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), SubscriptionLabActivity::class.java))
                true
            }

        findPreferenceNotNull<Preference>("nlic_network_health")
            .setOnPreferenceClickListener {
                showNetworkHealth()
                true
            }

        findPreferenceNotNull<Preference>("proxy_test")
            .setOnPreferenceClickListener {
                val intent = Intent(context, TestActivity::class.java)
                startActivity(intent)
                true
            }

        findPreferenceNotNull<Preference>("battery_optimization")
            .setOnPreferenceClickListener {
                PermissionUtils.requestBatteryOptimization(requireContext())
                true
            }


        findPreferenceNotNull<Preference>("donate")
            .setOnPreferenceClickListener {
                showDonateDialog()
                true
            }

        findPreferenceNotNull<Preference>("dns_ip_picker")
            .setOnPreferenceClickListener {
                if (PrivateDnsUtils.getState(requireContext()) is PrivateDnsState.Configured) {
                    updatePreferences()
                    PrivateDnsUtils.openSettings(requireContext())
                } else {
                    showDnsDialog()
                }
                true
            }

        findPreferenceNotNull<Preference>("private_dns")
            .setOnPreferenceClickListener {
                PrivateDnsUtils.openSettings(requireContext())
                true
            }

        findPreferenceNotNull<Preference>("version").summary = BuildConfig.VERSION_NAME
        findPreferenceNotNull<Preference>("byedpi_version").summary = "0.17.3 (ba53229)"

        updatePreferences()
    }

    override fun onResume() {
        super.onResume()
        sharedPreferences?.registerOnSharedPreferenceChangeListener(preferenceListener)
        updatePreferences()
    }

    override fun onPause() {
        super.onPause()
        sharedPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
    }

    private fun updatePreferences() {
        val cmdEnable = findPreferenceNotNull<SwitchPreference>("byedpi_enable_cmd_settings").isChecked
        val mode = findPreferenceNotNull<ListPreference>("byedpi_mode").value.let { Mode.fromString(it) }
        val dns = findPreferenceNotNull<Preference>("dns_ip_picker")
        val dnsEdit = findPreferenceNotNull<EditTextPreference>("dns_ip")
        val privateDns = findPreferenceNotNull<Preference>("private_dns")
        val privateDnsState = PrivateDnsUtils.getState(requireContext())
        val ipv6 = findPreferenceNotNull<SwitchPreference>("ipv6_enable")
        val proxy = findPreferenceNotNull<PreferenceCategory>("byedpi_proxy_category")

        val applistType = findPreferenceNotNull<ListPreference>("applist_type")
        val selectedApps = findPreferenceNotNull<Preference>("selected_apps")
        val batteryOptimization = findPreferenceNotNull<Preference>("battery_optimization")

        val uiSettings = findPreferenceNotNull<Preference>("byedpi_ui_settings")
        val cmdSettings = findPreferenceNotNull<Preference>("byedpi_cmd_settings")
        val proxyTest = findPreferenceNotNull<Preference>("proxy_test")

        if (cmdEnable) {
            val (cmdIp, cmdPort) = sharedPreferences?.checkIpAndPortInCmd() ?: Pair(null, null)
            proxy.isVisible = cmdIp == null && cmdPort == null
        } else {
            proxy.isVisible = true
        }

        uiSettings.isEnabled = !cmdEnable
        cmdSettings.isEnabled = cmdEnable
        proxyTest.isEnabled = cmdEnable
        if (BuildConfig.NLIC_LIBBOX_INCLUDED) {
            // N-LIC is a managed VPN client, not a shell-argument editor.
            // Legacy settings remain on disk for migration/rollback but
            // cannot be edited via the 2.0 primary UI.
            findPreferenceNotNull<SwitchPreference>("byedpi_enable_cmd_settings").isVisible = false
            cmdSettings.isVisible = false
            proxyTest.isEnabled = true
        }

        dns.summary = dnsSummary()
        dnsEdit.isVisible = false
        privateDns.summary = (privateDnsState as? PrivateDnsState.Configured)?.hostname
        privateDns.isVisible = mode == Mode.VPN && privateDnsState is PrivateDnsState.Configured

        when (mode) {
            Mode.VPN -> {
                dns.isVisible = privateDnsState !is PrivateDnsState.Configured
                ipv6.isVisible = true

                when (applistType.value) {
                    "disable" -> {
                        applistType.isVisible = true
                        selectedApps.isVisible = false
                    }
                    "blacklist", "whitelist" -> {
                        applistType.isVisible = true
                        selectedApps.isVisible = true
                    }
                    else -> {
                        applistType.isVisible = true
                        selectedApps.isVisible = false
                        Log.w(TAG, "Unexpected applistType value: ${applistType.value}")
                    }
                }
            }

            Mode.Proxy -> {
                dns.isVisible = false
                ipv6.isVisible = false
                applistType.isVisible = false
                selectedApps.isVisible = false
            }
        }

        if (PermissionUtils.isBatteryOptimizationDisabled(requireContext())) {
            batteryOptimization.summary = getString(R.string.battery_optimization_disabled_summary)
        } else {
            batteryOptimization.summary = getString(R.string.battery_optimization_summary)
        }

    }

    private fun showNetworkHealth() {
        val context = requireContext()
        val vpnActive = NativeEngine.running(context)?.first == Mode.VPN ||
            (appStatus.first == AppStatus.Running && appStatus.second == Mode.VPN)
        val physicalAvailable = runCatching {
            NetworkHealthUtils.underlyingNetwork(context) != null
        }.getOrDefault(false)
        // Without N-LIC VPN, an unavailable ConnectivityManager network list
        // must not prevent a plain default-route TCP/HTTPS diagnostic.
        // Never silently disconnect any third-party VPN.

        if (vpnActive && !physicalAvailable) {
            // A VPN may be the only Network visible to our UID. Ask BEFORE
            // disconnecting; restoring the same N-LIC mode is non-optional.
            AlertDialog.Builder(context)
                .setTitle(R.string.nlic_network_health_paused_title)
                .setMessage(R.string.nlic_network_health_paused_warning)
                .setPositiveButton(R.string.nlic_network_health_paused_confirm) { _, _ ->
                    runNetworkHealth(temporarilyStopVpn = true)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            runNetworkHealth(temporarilyStopVpn = false)
        }
    }

    private fun runNetworkHealth(temporarilyStopVpn: Boolean) {
        val app = requireContext().applicationContext
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.nlic_network_health)
            .setMessage(R.string.nlic_network_health_running)
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(false)
            .create()
        dialog.show()
        val job = viewLifecycleOwner.lifecycleScope.launch {
            var stopped = false
            var restored = !temporarilyStopVpn
            var report: io.github.romanvht.byedpi.utility.NetworkHealthReport? = null
            var failure: String? = null
            try {
                if (temporarilyStopVpn) {
                    // Mark restoration required before invoking stop, because
                    // cancellation may happen at any suspension point.
                    stopped = true
                    ServiceManager.waitStop()
                    delay(1_000) // Give Android time to update default Network.
                }
                report = withTimeoutOrNull(105_000) {
                    NetworkHealthUtils.run(app, compareSocks = !temporarilyStopVpn)
                }
                if (report == null) failure = getString(R.string.nlic_network_health_timeout)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                failure = e.message?.take(120) ?: "Network diagnostic failed"
                Log.w(TAG, "Physical network diagnostic failed", e)
            } finally {
                if (stopped) {
                    // Even when the user cancels the scan or navigates away,
                    // always attempt to restore their previously running VPN.
                    withContext(NonCancellable) {
                        restored = try {
                            if (VpnService.prepare(app) == null) {
                                withTimeoutOrNull(25_000) {
                                    ServiceManager.waitStart(app, Mode.VPN)
                                } == true
                            } else false
                        } catch (e: Exception) {
                            Log.e(TAG, "Could not restore N-LIC VPN after diagnostics", e)
                            false
                        }
                    }
                }
            }
            if (temporarilyStopVpn && restored && report != null) {
                report = withTimeoutOrNull(18_000) {
                    NetworkHealthUtils.addProxyComparison(app, requireNotNull(report))
                } ?: report
            }
            dialog.dismiss()
            if (!isAdded) return@launch
            val text = buildString {
                report?.let { appendLine(it.asText()) }
                failure?.let { appendLine(it) }
                if (temporarilyStopVpn) {
                    appendLine(getString(
                        if (restored) R.string.nlic_network_health_restored
                        else R.string.nlic_network_health_restore_failed
                    ))
                }
            }
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.nlic_network_health)
                .setMessage(text)
                .setPositiveButton(R.string.diagnostic_copy) { _, _ ->
                    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("N-LIC physical network report", text)
                    )
                    Toast.makeText(requireContext(), R.string.toast_copied, Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton(R.string.nlic_network_health_app_access) { _, _ ->
                    startActivity(Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", requireContext().packageName, null),
                    ))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            job.cancel()
            dialog.dismiss()
        }
    }

    private val dnsPresets: List<Pair<String, String>>
        get() = listOf(
            "" to getString(R.string.dns_system),
            "8.8.8.8" to getString(R.string.dns_google),
            "1.1.1.1" to getString(R.string.dns_cloudflare),
            "9.9.9.9" to getString(R.string.dns_quad9),
            "77.88.8.8" to getString(R.string.dns_yandex),
        )

    private fun dnsSummary(): String {
        val ip = sharedPreferences?.getStringNotNull("dns_ip", "1.1.1.1") ?: "1.1.1.1"
        val preset = dnsPresets.firstOrNull { it.first == ip } ?: return ip
        return if (preset.first.isBlank()) preset.second else "${preset.second} ($ip)"
    }

    private fun showDnsDialog() {
        val context = requireContext()
        val presets = dnsPresets

        val items = (presets.map { it.second } + getString(R.string.dns_custom)).toTypedArray()
        val dnsPref = findPreferenceNotNull<EditTextPreference>("dns_ip")

        AlertDialog.Builder(context)
            .setTitle(getString(R.string.dbs_ip_setting))
            .setItems(items) { _, which ->
                if (which < presets.size) {
                    sharedPreferences?.edit { putString("dns_ip", presets[which].first) }
                } else {
                    onDisplayPreferenceDialog(dnsPref)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDonateDialog() {
        val context = requireContext()

        val items = arrayOf(
            getString(R.string.donate_message),
            getString(R.string.donate_cloudtips),
            getString(R.string.donate_boosty),
            getString(R.string.donate_telegram_bot),
            getString(R.string.donate_thanks),
        )

        AlertDialog.Builder(context)
            .setTitle(getString(R.string.donate))
            .setItems(items) { _, which ->
                when (which) {
                    1 -> openUrl("https://pay.cloudtips.ru/p/92c754db")
                    2 -> openUrl("https://boosty.to/romanvht/donate")
                    3 -> openUrl("https://t.me/romanvht_donate_bot")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        startActivity(intent)
    }
}
