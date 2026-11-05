package io.github.romanvht.byedpi.activities

import android.app.Activity
import android.content.res.ColorStateList
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import androidx.core.content.ContextCompat
import android.net.VpnService
import android.os.Bundle
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ScrollView
import android.util.TypedValue
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.romanvht.byedpi.BuildConfig
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.data.AppStatus
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.services.RealityCompareService
import io.github.romanvht.byedpi.services.appStatus
import io.github.romanvht.byedpi.subscription.LICAuto
import io.github.romanvht.byedpi.subscription.SingBoxConfigCompiler
import io.github.romanvht.byedpi.subscription.XrayConfigCompiler
import io.github.romanvht.byedpi.subscription.LocalSocksAuth
import io.github.romanvht.byedpi.subscription.RealityAssistTrace
import io.github.romanvht.byedpi.subscription.RealityAssistPolicy
import io.github.romanvht.byedpi.subscription.RealityAssistMemory
import io.github.romanvht.byedpi.subscription.SubscriptionCatalog
import io.github.romanvht.byedpi.subscription.SubscriptionFetcher
import io.github.romanvht.byedpi.subscription.SubscriptionGroup
import io.github.romanvht.byedpi.subscription.SubscriptionImport
import io.github.romanvht.byedpi.subscription.SubscriptionNode
import io.github.romanvht.byedpi.subscription.SubscriptionVault
import io.github.romanvht.byedpi.subscription.VPNFileImport
import io.github.romanvht.byedpi.utility.AdaptiveDpiPolicy
import io.github.romanvht.byedpi.utility.getPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * A dedicated, single-screen VPN control centre.
 * Core mode is explicitly opt-in; a failed core start never silently falls back
 * to direct traffic or to a different server. Only safe stage codes are shown.
 */
class SubscriptionLabActivity : BaseActivity() {
    private val prefs by lazy { getPreferences() }
    private var busy = false
    private var locallyVerified = false
    private var latestMessage: String? = null
    private var comparisonRunning = false
    private var comparisonResult: String? = null
    private var comparisonDetails: String? = null
    private var comparisonConnection: ServiceConnection? = null
    private val comparisonReceiver by lazy {
        Messenger(Handler(Looper.getMainLooper()) { message ->
            if (comparisonRunning) {
                val content = message.data?.getString(RealityCompareService.TEXT).orEmpty()
                when (message.what) {
                    RealityCompareService.PROGRESS -> {
                        comparisonResult = content
                        showComparisonResult()
                    }
                    RealityCompareService.FINISHED -> {
                        val compact = message.data
                            ?.getString(RealityCompareService.COMPACT)
                        endRealityComparison(content, compact, showDialog = true)
                    }
                    RealityCompareService.ERROR -> endRealityComparison(content)
                }
            }
            true
        })
    }

    // Only repaint the single status line. Rebuilding the entire server
    // selector every second during handshake would add unnecessary CPU work.
    private var realityStatusVisible = false
    private val realityStatusTicker = object : Runnable {
        override fun run() {
            if (!realityStatusVisible) return
            updateRealityAssistStatus()
            window.decorView.postDelayed(this, 800L)
        }
    }

    private enum class ConnectionMode { LOCAL, SUBSCRIPTION }

    private fun connectionMode(): ConnectionMode =
        if (prefs.getString("nlic_connection_mode", "subscription") == "local") {
            ConnectionMode.LOCAL
        } else ConnectionMode.SUBSCRIPTION

    private fun chooseMode(next: ConnectionMode) {
        if (busy || connectionMode() == next) return
        val save: () -> Unit = {
            prefs.edit().putString("nlic_connection_mode",
                if (next == ConnectionMode.LOCAL) "local" else "subscription").commit()
            locallyVerified = false
            latestMessage = null
            render()
        }
        if (appStatus.first == AppStatus.Running) {
            AlertDialog.Builder(this)
                .setTitle(R.string.nlic_mode_confirm_title)
                .setMessage(R.string.nlic_mode_confirm_body)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runOperation {
                        ServiceManager.waitStop()
                        prefs.edit().putBoolean("nlic_core_mode", false).commit()
                        save()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null).show()
        } else save()
    }

    private fun updateModeButton(button: MaterialButton, active: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(
            this, if (active) R.color.nlic_surface_raised else R.color.nlic_canvas))
        button.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(
            this, if (active) R.color.nlic_accent else R.color.nlic_border))
        button.setTextColor(ContextCompat.getColor(
            this, if (active) R.color.nlic_text else R.color.nlic_muted))
        button.isEnabled = !busy
    }

    // SAF grants access only to a file explicitly chosen by the user.
    // No broad storage permission, no provider URI is retained or logged.
    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importChosenFile(uri)
    }

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) startCore()
        else toast(R.string.vpn_permission_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nlic_control)
        setupToolbar()
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.title = "N-LIC"
        // ActionBar owns Toolbar menu dispatch. Bind a real view instead
        // so Settings stays available even when the support menu is rebuilt.
        findViewById<androidx.appcompat.widget.AppCompatImageButton>(
            R.id.nlic_settings_button
        ).setOnClickListener { showCompactSettings() }

        findViewById<MaterialButton>(R.id.nlic_manage_profiles).setOnClickListener {
            if (busy) return@setOnClickListener
            val labels = arrayOf(
                getString(R.string.nlic_control_add_subscription),
                getString(R.string.nlic_control_add_link),
                getString(R.string.nlic_home_file_import),
                getString(R.string.nlic_home_refresh_subscriptions)
            )
            AlertDialog.Builder(this)
                .setTitle(R.string.nlic_profiles_manage)
                .setItems(labels) { _, selected ->
                    when (selected) {
                        0 -> showImportDialog(subscription = true)
                        1 -> showImportDialog(subscription = false)
                        2 -> filePicker.launch(arrayOf("*/*"))
                        3 -> refreshSubscriptions()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        findViewById<MaterialButton>(R.id.nlic_mode_bypass).setOnClickListener {
            chooseMode(ConnectionMode.LOCAL)
        }
        findViewById<MaterialButton>(R.id.nlic_mode_subscription).setOnClickListener {
            chooseMode(ConnectionMode.SUBSCRIPTION)
        }
        findViewById<MaterialButton>(R.id.nlic_reality_compare_button).setOnClickListener {
            if (comparisonRunning) {
                endRealityComparison(getString(R.string.nlic_reality_compare_cancelled))
            } else if (!busy && connectionMode() == ConnectionMode.SUBSCRIPTION) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.nlic_reality_compare_choose)
                    .setItems(arrayOf(
                        getString(R.string.nlic_reality_compare_quick),
                        getString(R.string.nlic_reality_compare_extended),
                        getString(R.string.nlic_reality_compare_stream)
                    )) { _, choice ->
                        startRealityComparison(
                            when (choice) {
                                1 -> 18
                                2 -> 3 // 3×512 KiB through selected REALITY
                                else -> 6
                            }
                        )
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        findViewById<MaterialButton>(R.id.nlic_connect).setOnClickListener {
            if (appStatus.first == AppStatus.Running && appStatus.second == Mode.VPN)
                disconnectCore()
            else requestConnection()
        }
        render()
        // The home power button starts this Activity with an explicit visible
        // user gesture. Never autoplay VPN merely because Activity resumed
        // or was restored from background.
        if (savedInstanceState == null &&
            intent.getBooleanExtra("nlic_quick_connect", false)) {
            window.decorView.post { requestConnection() }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        realityStatusVisible = true
        window.decorView.removeCallbacks(realityStatusTicker)
        window.decorView.post(realityStatusTicker)
    }

    override fun onPause() {
        realityStatusVisible = false
        window.decorView.removeCallbacks(realityStatusTicker)
        super.onPause()
    }

    override fun onStop() {
        // Explicit foreground-only test. Unbinding cancels the isolated
        // service, so it cannot keep probing in the background.
        if (comparisonRunning) {
            endRealityComparison(getString(R.string.nlic_reality_compare_cancelled))
        }
        super.onStop()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun showCompactSettings() = NlicV2Settings.show(
        this, appStatus.first == AppStatus.Running,
        onRealityModeChanged = { render() },
    ) {
        runOperation {
            ServiceManager.waitStop()
            prefs.edit().putBoolean("nlic_core_mode", false)
                .putBoolean("nlic_core_auto", false)
                .putBoolean("nlic_core_bay", false).commit()
            withContext(Dispatchers.IO) {
                SubscriptionVault.clear(applicationContext)
            }
            latestMessage = null
            locallyVerified = false
        }
    }

    private fun toast(id: Int) =
        Toast.makeText(this, id, Toast.LENGTH_LONG).show()

    private fun showImportDialog(subscription: Boolean) {
        if (busy) return
        val entry = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = if (subscription) 2 else 4
            maxLines = 8
            setPadding(24, 16, 24, 16)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            if (subscription) setText("https://")
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (subscription) R.string.nlic_subscriptions_add_url
                else R.string.nlic_subscriptions_add_link)
            .setView(entry)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = entry.text.toString().trim()
                entry.text.clear()
                if (text.isEmpty()) return@setPositiveButton
                if (subscription) importSubscription(text) else importLinks(text)
            }
            .setNegativeButton(android.R.string.cancel, null).create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun importChosenFile(uri: android.net.Uri) = runOperation {
        val filename = withContext(Dispatchers.IO) {
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
                    null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()?.take(120) ?: "import.txt"
        }
        val content = withContext(Dispatchers.IO) {
            val stream = contentResolver.openInputStream(uri)
                ?: error("Selected document is unavailable")
            stream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val bytes = input.read(buffer)
                    if (bytes == -1) break
                    require(output.size() + bytes <= VPNFileImport.MAX_BYTES) {
                        "File too large"
                    }
                    output.write(buffer, 0, bytes)
                }
                output.toString(Charsets.UTF_8.name())
            }
        }
        val nodes = withContext(Dispatchers.Default) {
            VPNFileImport.parse(filename, content)
        }
        withContext(Dispatchers.IO) {
            SubscriptionVault.addManual(applicationContext, nodes)
            selectFirstIfNeeded()
        }
        latestMessage = getString(R.string.nlic_home_file_imported, nodes.size)
    }

    private fun importLinks(text: String) = runOperation {
        val nodes = withContext(Dispatchers.Default) { SubscriptionImport.parse(text) }
        require(nodes.isNotEmpty()) { "NO_NODES" }
        withContext(Dispatchers.IO) {
            SubscriptionVault.addManual(applicationContext, nodes)
            selectFirstIfNeeded()
        }
        latestMessage = getString(R.string.nlic_subscriptions_imported, nodes.size)
    }

    /** The app UID is excluded from its own VPN TUN to prevent routing
     * loops. Subscription refresh may therefore use the physical network.
     * HTTPS protects credentials in transit but not necessarily the domain.
     */
    private fun confirmSubscriptionFetch(action: () -> Unit) {
        val activeVpn = appStatus.first == AppStatus.Running &&
            appStatus.second == Mode.VPN
        if (!activeVpn) {
            action()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.nlic_subscription_fetch_title)
            .setMessage(R.string.nlic_subscription_fetch_privacy)
            .setPositiveButton(android.R.string.ok) { _, _ -> action() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importSubscription(url: String) {
        confirmSubscriptionFetch { performImportSubscription(url) }
    }

    private fun performImportSubscription(url: String) = runOperation {
        require(SubscriptionFetcher.validSubscriptionUrl(url)) { "INVALID_URL" }
        val nodes = withContext(Dispatchers.IO) {
            val body = SubscriptionFetcher.fetch(url)
            val hostLabel = URI(url).host ?: "Subscription"
            val parsed = if (body.trimStart().startsWith("{") ||
                body.trimStart().startsWith("[")) {
                VPNFileImport.parse("$hostLabel.json", body)
            } else {
                SubscriptionImport.parse(body, hostLabel)
            }
            require(parsed.isNotEmpty()) { "NO_NODES" }
            SubscriptionVault.updateGroup(applicationContext,
                SubscriptionGroup(hostLabel, url, parsed, System.currentTimeMillis()))
            selectFirstIfNeeded()
            parsed
        }
        latestMessage = getString(R.string.nlic_subscriptions_imported, nodes.size)
    }

    private fun selectFirstIfNeeded() {
        val store = SubscriptionVault.read(applicationContext)
        if (store.selectedNodeId != null &&
            store.allNodes.any { it.id == store.selectedNodeId }) return
        val first = store.allNodes.firstOrNull { node ->
            if (BuildConfig.NLIC_XRAY_INCLUDED) {
                XrayConfigCompiler.compile(node, LocalSocksAuth("nlic", "a".repeat(48))) is
                    XrayConfigCompiler.Result.Compiled
            } else SingBoxConfigCompiler.compile(node) is
                SingBoxConfigCompiler.Result.Compiled
        }
        if (first != null) SubscriptionVault.select(applicationContext, first.id)
    }

    private fun refreshSubscriptions() {
        confirmSubscriptionFetch { performRefreshSubscriptions() }
    }

    private fun performRefreshSubscriptions() = runOperation {
        val groups = withContext(Dispatchers.IO) {
            SubscriptionVault.read(applicationContext).groups
        }
        if (groups.isEmpty()) {
            latestMessage = getString(R.string.nlic_control_no_subscriptions)
            return@runOperation
        }
        var count = 0
        var failures = 0
        for (group in groups.take(30)) {
            val updated = runCatching {
                withContext(Dispatchers.IO) {
                    val body = SubscriptionFetcher.fetch(group.url)
                    val nodes = if (body.trimStart().startsWith("{") ||
                        body.trimStart().startsWith("[")) {
                        VPNFileImport.parse("${group.title}.json", body)
                    } else {
                        SubscriptionImport.parse(body, group.title)
                    }
                    if (nodes.isNotEmpty()) {
                        SubscriptionVault.updateGroup(applicationContext,
                            group.copy(nodes = nodes, refreshedAt = System.currentTimeMillis()))
                    }
                    nodes.size
                }
            }.getOrElse {
                failures++
                0 // Last known-good encrypted cache remains untouched.
            }
            count += updated
        }
        latestMessage = if (failures == 0) {
            getString(R.string.nlic_subscriptions_refreshed, count)
        } else {
            getString(R.string.nlic_home_refresh_partial, count, failures)
        }
    }

    private fun checkTcpEndpoints() {
        AlertDialog.Builder(this).setTitle(R.string.nlic_auto_label)
            .setMessage(R.string.nlic_auto_consent)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                runOperation {
                    val nodes = withContext(Dispatchers.IO) {
                        SubscriptionVault.read(applicationContext).allNodes
                    }
                    val results = LICAuto.check(nodes)
                    if (results.isEmpty()) {
                        latestMessage = getString(R.string.nlic_control_no_servers)
                    } else {
                        latestMessage = results.take(5).joinToString("\n") { r ->
                            r.node.name + " · " + (r.medianConnectMs?.toString() ?: "—") +
                                " ms · " + r.successes + "/" + r.attempts + " TCP"
                        }
                    }
                    AlertDialog.Builder(this@SubscriptionLabActivity)
                        .setTitle(R.string.nlic_auto_results)
                        .setMessage(latestMessage)
                        .setPositiveButton(android.R.string.ok, null).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun requestConnection() {
        if (busy) return
        if (connectionMode() == ConnectionMode.SUBSCRIPTION) {
            if (!BuildConfig.NLIC_LIBBOX_INCLUDED && !BuildConfig.NLIC_XRAY_INCLUDED) {
                toast(R.string.nlic_core_missing)
                return
            }
            if (!canConnect(readCatalog())) {
                toast(R.string.nlic_core_choose_supported)
                return
            }
        }
        if (prefs.getString("byedpi_mode", "vpn") != "vpn") {
            AlertDialog.Builder(this)
                .setTitle(R.string.nlic_control_mode_title)
                .setMessage(R.string.nlic_control_mode_desc)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    prefs.edit().putString("byedpi_mode", "vpn").commit()
                    requestPermission()
                }
                .setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        if (appStatus.first == AppStatus.Running) {
            AlertDialog.Builder(this)
                .setTitle(R.string.nlic_control_reconnect_title)
                .setMessage(R.string.nlic_control_reconnect_desc)
                .setPositiveButton(android.R.string.ok) { _, _ -> requestPermission() }
                .setNegativeButton(android.R.string.cancel, null).show()
        } else requestPermission()
    }

    private fun requestPermission() {
        val permission = VpnService.prepare(this)
        if (permission == null) startCore() else vpnPermission.launch(permission)
    }

    private fun startCore() {
        if (busy) return
        runOperation {
            // Fully stop legacy VPN before changing persistent core state.
            ServiceManager.waitStop()
            prefs.edit().putBoolean("nlic_core_mode",
                connectionMode() == ConnectionMode.SUBSCRIPTION).commit()
            var success = false
            try {
                success = ServiceManager.waitStart(applicationContext, Mode.VPN)
                locallyVerified = success
                if (success) latestMessage = getString(
                    if (connectionMode() == ConnectionMode.LOCAL) R.string.nlic_bypass_connected
                    else R.string.nlic_control_verified
                )
                else latestMessage = getString(R.string.nlic_core_connect_failed)
            } finally {
                if (!success) {
                    // No automatic direct fallback or unsafe implicit ByeDPI start.
                    prefs.edit().putBoolean("nlic_core_mode", false).commit()
                    locallyVerified = false
                }
            }
        }
    }

    private fun disconnectCore() = runOperation {
        ServiceManager.waitStop()
        prefs.edit().putBoolean("nlic_core_mode", false).commit()
        locallyVerified = false
        latestMessage = null
    }

    private fun readCatalog(): SubscriptionCatalog =
        runCatching { SubscriptionVault.read(applicationContext) }
            .getOrDefault(SubscriptionCatalog())

    private fun canConnect(catalog: SubscriptionCatalog): Boolean {
        if (connectionMode() == ConnectionMode.LOCAL) return true
        if (BuildConfig.NLIC_XRAY_INCLUDED) {
            val bay = prefs.getBoolean("nlic_core_bay", false)
            val relayPort = if (bay) XrayConfigCompiler.BAY_PORT else null
            val auth = LocalSocksAuth("nlic", "a".repeat(48))
            val supported: (SubscriptionNode) -> Boolean = { node ->
                XrayConfigCompiler.compile(node, auth, relayPort) is
                    XrayConfigCompiler.Result.Compiled
            }
            return if (prefs.getBoolean("nlic_core_auto", false)) {
                io.github.romanvht.byedpi.subscription.XrayAutoSelector.candidates(
                    catalog.allNodes, catalog.selectedNodeId, bay, supported
                ).isNotEmpty()
            } else {
                catalog.allNodes.any { it.id == catalog.selectedNodeId && supported(it) }
            }
        }
        if (!BuildConfig.NLIC_LIBBOX_INCLUDED) return false
        val bay = if (prefs.getBoolean("nlic_core_bay", false))
            SingBoxConfigCompiler.Bay.ON else SingBoxConfigCompiler.Bay.OFF
        return if (prefs.getBoolean("nlic_core_auto", false)) {
            SingBoxConfigCompiler.compileAuto(catalog.allNodes, bay) is
                SingBoxConfigCompiler.Result.Compiled
        } else {
            val selected = catalog.allNodes.firstOrNull { it.id == catalog.selectedNodeId }
                ?: return false
            SingBoxConfigCompiler.compile(selected, bay) is SingBoxConfigCompiler.Result.Compiled
        }
    }

    private fun failureAdvice(code: String): String = when {
        code.startsWith("NATIVE_AT_") ->
            getString(R.string.nlic_core_advice_native_crash)
        code == "PROTOCOL_UNSUPPORTED" ->
            getString(R.string.nlic_core_advice_config)
        code == "REMOTE_TLS_CERT" ->
            getString(R.string.nlic_core_advice_tls_cert)
        code == "REMOTE_TLS_TIMEOUT" ->
            getString(R.string.nlic_core_advice_tls_timeout)
        code == "REMOTE_TLS_PROTOCOL" || code == "REMOTE_TLS_HANDSHAKE" ->
            getString(R.string.nlic_core_advice_tls_handshake)
        code == "REMOTE_TLS_EOF" || code == "REMOTE_TLS_IO" ->
            getString(R.string.nlic_core_advice_tls_eof)
        code == "AUTO_NO_WORKING_SERVER" ->
            getString(R.string.nlic_xray_auto_failed)
        code.startsWith("BAY_") ->
            getString(R.string.nlic_core_advice_bay)
        code.startsWith("XRAY_") ->
            getString(R.string.nlic_core_advice_engine)
        code.startsWith("CORE_SOCKS") ->
            getString(R.string.nlic_core_advice_socks)
        code.startsWith("REMOTE_") || code == "TUNNEL_TEST" ->
            getString(R.string.nlic_core_advice_https)
        code.startsWith("TUN") || code.startsWith("HEV") ->
            getString(R.string.nlic_core_advice_tun)
        code == "CONFIG" || code == "CONFIG_COMPILE" ->
            getString(R.string.nlic_core_advice_config)
        code == "CATALOG" || code == "PREPARE" ->
            getString(R.string.nlic_core_advice_catalog)
        else -> getString(R.string.nlic_core_advice_engine)
    }

    private fun render() {
        val local = connectionMode() == ConnectionMode.LOCAL
        findViewById<View>(R.id.nlic_bypass_panel).visibility =
            if (local) View.VISIBLE else View.GONE
        findViewById<View>(R.id.nlic_subscription_panel).visibility =
            if (local) View.GONE else View.VISIBLE
        updateModeButton(findViewById(R.id.nlic_mode_bypass), local)
        updateModeButton(findViewById(R.id.nlic_mode_subscription), !local)
        val catalog = readCatalog()
        val all = catalog.allNodes
        val selected = all.firstOrNull { it.id == catalog.selectedNodeId }
        val auto = prefs.getBoolean("nlic_core_auto", false)
        val bay = prefs.getBoolean("nlic_core_bay", false)
        val title = findViewById<TextView>(R.id.nlic_status_label)
        val details = findViewById<TextView>(R.id.nlic_status_message)
        val stage = findViewById<TextView>(R.id.nlic_stage_label)
        val runningVpn = appStatus.first == AppStatus.Running && appStatus.second == Mode.VPN
        title.setText(when {
            comparisonRunning -> R.string.nlic_reality_compare_in_progress
            busy -> R.string.nlic_core_connecting
            runningVpn -> R.string.nlic_control_running
            else -> R.string.nlic_control_disconnected
        })
        val failure = if (local) null else ServiceManager.lastCoreFailureCode
        details.text = when {
            comparisonRunning -> getString(R.string.nlic_reality_compare_description)
            busy -> getString(R.string.nlic_control_working)
            failure != null && !runningVpn -> failureAdvice(failure)
            latestMessage != null -> latestMessage
            local && runningVpn -> getString(R.string.nlic_bypass_connected)
            local -> getString(R.string.nlic_bypass_status_hint)
            !BuildConfig.NLIC_LIBBOX_INCLUDED && !BuildConfig.NLIC_XRAY_INCLUDED ->
                getString(R.string.nlic_core_missing)
            all.isEmpty() -> getString(R.string.nlic_control_start_hint)
            runningVpn && !locallyVerified -> getString(R.string.nlic_control_android_only)
            runningVpn -> getString(R.string.nlic_control_verified)
            else -> getString(R.string.nlic_control_status_hint)
        }
        stage.visibility = if (!local && failure != null && !runningVpn)
            View.VISIBLE else View.GONE
        stage.text = when {
            local && runningVpn -> getString(R.string.nlic_bypass_status)
            local -> getString(R.string.nlic_mode_local)
            runningVpn && locallyVerified -> getString(R.string.nlic_control_https_ok)
            failure != null -> getString(R.string.nlic_control_stage_failure, failure)
            else -> getString(R.string.nlic_control_preview_badge)
        }
        findViewById<View>(R.id.nlic_progress).visibility =
            if (busy) View.VISIBLE else View.GONE

        val choices = all.take(150)
        val labels = choices.map { node -> node.name + "  ·  " + node.protocol }
        findViewById<MaterialButton>(R.id.nlic_server_picker_button).apply {
            text = if (auto) {
                if (BuildConfig.NLIC_XRAY_INCLUDED && runningVpn && selected != null) {
                    getString(R.string.nlic_control_auto_selected) + " · " +
                        selected.name + " · " + selected.protocol
                } else getString(R.string.nlic_control_auto_selected)
            } else selected?.let { it.name + "  ·  " + it.protocol }
                ?: getString(R.string.nlic_control_select_server)
            isEnabled = !busy && choices.isNotEmpty()
            setOnClickListener {
                val selectedPosition = choices.indexOfFirst { node -> node.id == selected?.id }
                AlertDialog.Builder(this@SubscriptionLabActivity)
                    .setTitle(R.string.nlic_control_server_title)
                    .setSingleChoiceItems(labels.toTypedArray(), selectedPosition) { dialog, position ->
                        val node = choices.getOrNull(position)
                        if (node != null) {
                            runCatching {
                                SubscriptionVault.select(applicationContext, node.id)
                            }.onSuccess {
                                if (auto) {
                                    prefs.edit().putBoolean("nlic_core_auto", false).commit()
                                }
                                val bayCompatible = if (BuildConfig.NLIC_XRAY_INCLUDED) {
                                    XrayConfigCompiler.compile(node, LocalSocksAuth(
                                        "nlic", "a".repeat(48)), XrayConfigCompiler.BAY_PORT
                                    ) is XrayConfigCompiler.Result.Compiled
                                } else SingBoxConfigCompiler.compile(
                                    node, SingBoxConfigCompiler.Bay.ON
                                ) is SingBoxConfigCompiler.Result.Compiled
                                if (bay && !bayCompatible) {
                                    prefs.edit().putBoolean("nlic_core_bay", false).commit()
                                }
                                latestMessage = null
                                locallyVerified = false
                                // A previous A/B report must never be shown
                                // as evidence for a newly chosen server.
                                comparisonResult = null
                                comparisonDetails = null
                                render()
                            }.onFailure { toast(R.string.nlic_subscriptions_error) }
                        }
                        dialog.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        findViewById<TextView>(R.id.nlic_server_hint).text = when {
            all.isEmpty() -> getString(R.string.nlic_control_start_hint)
            auto -> getString(R.string.nlic_core_auto_desc)
            selected == null -> getString(R.string.nlic_ui_step_choose)
            (if (BuildConfig.NLIC_XRAY_INCLUDED) {
                XrayConfigCompiler.compile(selected, LocalSocksAuth("nlic", "a".repeat(48))) is XrayConfigCompiler.Result.Compiled
            } else {
                SingBoxConfigCompiler.compile(selected) is SingBoxConfigCompiler.Result.Compiled
            }) -> getString(R.string.nlic_control_selected_ok)
            else -> getString(R.string.nlic_control_unsupported)
        }

        // Explicitly tell the user whether LIC-Assist is ON. A checked switch
        // reflects actual smart-mode preference, not a past connection result.
        // Never apply an incompatible setting or change a running VPN tunnel.
        val assistMode = prefs.getString(
            "nlic_reality_tcp_utls", RealityAssistPolicy.PROFILE
        ) ?: RealityAssistPolicy.PROFILE
        val realityCompatible = BuildConfig.NLIC_XRAY_INCLUDED &&
            selected?.let { node ->
                val plan = XrayConfigCompiler.compile(
                    node, LocalSocksAuth("nlic", "a".repeat(48))
                ) as? XrayConfigCompiler.Result.Compiled
                plan != null && RealityAssistPolicy.baselineFingerprint(plan.json) != null
            } == true
        val assistSwitch = findViewById<SwitchMaterial>(R.id.nlic_reality_smart_switch)
        val assistHint = findViewById<TextView>(R.id.nlic_reality_smart_hint)
        assistSwitch.setOnCheckedChangeListener(null)
        assistSwitch.isChecked = assistMode == RealityAssistPolicy.SMART
        assistSwitch.isEnabled = !busy && !runningVpn && !auto && realityCompatible
        assistHint.setText(when {
            runningVpn -> R.string.nlic_reality_smart_disconnect
            !realityCompatible -> R.string.nlic_reality_smart_need_server
            auto -> R.string.nlic_reality_smart_disable_auto
            assistMode == RealityAssistPolicy.SMART -> R.string.nlic_reality_smart_on
            assistMode == RealityAssistPolicy.FIREFOX ->
                R.string.nlic_reality_smart_manual_firefox
            assistMode == RealityAssistPolicy.CHROME ->
                R.string.nlic_reality_smart_manual_chrome
            assistMode == RealityAssistPolicy.SAFARI ->
                R.string.nlic_reality_smart_manual_safari
            else -> R.string.nlic_reality_smart_off
        })
        if (!runningVpn && assistMode == RealityAssistPolicy.SMART &&
            realityCompatible && !auto && selected != null) {
            val winner = RealityAssistMemory.load(
                applicationContext, selected.id,
                RealityAssistMemory.networkClass(applicationContext), bay
            )
            if (winner != null) {
                val name = when (winner.fingerprint) {
                    RealityAssistPolicy.CHROME -> "Chrome"
                    RealityAssistPolicy.FIREFOX -> "Firefox"
                    RealityAssistPolicy.SAFARI -> "Safari"
                    else -> "из подписки"
                }
                assistHint.text = "Подобрано для этой сети: $name · HTTPS проверен. " +
                    "На следующем подключении этот вариант будет первым."
            }
        }
        assistSwitch.setOnCheckedChangeListener { _, enabled ->
            if (busy || runningVpn || auto || !realityCompatible) {
                render()
                return@setOnCheckedChangeListener
            }
            prefs.edit().putString(
                "nlic_reality_tcp_utls",
                if (enabled) RealityAssistPolicy.SMART else RealityAssistPolicy.PROFILE
            ).commit()
            // The last connection's trace must not be mistaken for
            // the freshly changed setting before a new VPN connection.
            RealityAssistTrace.clear(applicationContext)
            latestMessage = null
            locallyVerified = false
            render()
        }

        // Comparison is user-initiated and independent of the Smart switch.
        // Only the manually selected REALITY TCP/RAW server is measured;
        // no active VPN, automatic node selection or ByeDPI relay is allowed.
        findViewById<MaterialButton>(R.id.nlic_reality_compare_button).apply {
            isEnabled = comparisonRunning ||
                (!busy && appStatus.first != AppStatus.Running &&
                    !auto && !bay && realityCompatible)
            setText(if (comparisonRunning) R.string.nlic_reality_compare_cancel
                else R.string.nlic_reality_compare_button)
        }
        showComparisonResult()

        val autoSwitch = findViewById<SwitchMaterial>(R.id.nlic_auto_switch)
        autoSwitch.setOnCheckedChangeListener(null)
        autoSwitch.isChecked = auto
        autoSwitch.isEnabled = !busy &&
            (BuildConfig.NLIC_LIBBOX_INCLUDED || BuildConfig.NLIC_XRAY_INCLUDED) &&
            all.isNotEmpty()
        autoSwitch.setOnCheckedChangeListener { _, value ->
            prefs.edit().putBoolean("nlic_core_auto", value).commit()
            latestMessage = null
            render()
        }
        val bayEligible = if (BuildConfig.NLIC_XRAY_INCLUDED) {
            val auth = LocalSocksAuth("nlic", "a".repeat(48))
            val isValid: (SubscriptionNode) -> Boolean = { node ->
                XrayConfigCompiler.compile(node, auth, XrayConfigCompiler.BAY_PORT) is
                    XrayConfigCompiler.Result.Compiled
            }
            if (auto) all.any(isValid) else selected?.let(isValid) == true
        } else if (auto) all.any {
            SingBoxConfigCompiler.compile(it, SingBoxConfigCompiler.Bay.ON) is
                SingBoxConfigCompiler.Result.Compiled
        } else selected?.let {
            SingBoxConfigCompiler.compile(it, SingBoxConfigCompiler.Bay.ON) is
                SingBoxConfigCompiler.Result.Compiled
        } == true
        val baySwitch = findViewById<SwitchMaterial>(R.id.nlic_bay_switch)
        baySwitch.setOnCheckedChangeListener(null)
        baySwitch.isChecked = bay
        baySwitch.isEnabled = !busy &&
            (BuildConfig.NLIC_LIBBOX_INCLUDED || BuildConfig.NLIC_XRAY_INCLUDED) &&
            (bay || bayEligible)
        baySwitch.setOnCheckedChangeListener { _, value ->
            prefs.edit().putBoolean("nlic_core_bay", value).commit()
            latestMessage = null
            render()
        }
        findViewById<SwitchMaterial>(R.id.nlic_adaptive_switch).apply {
            setOnCheckedChangeListener(null)
            isChecked = prefs.getBoolean(AdaptiveDpiPolicy.KEY, true)
            isEnabled = !busy
            setOnCheckedChangeListener { _, enabled ->
                prefs.edit().putBoolean(AdaptiveDpiPolicy.KEY, enabled).commit()
                latestMessage = getString(R.string.nlic_home_policy_description)
                render()
            }
        }

        findViewById<MaterialButton>(R.id.nlic_connect).apply {
            isEnabled = !busy && (runningVpn || local || canConnect(catalog))
            setText(if (runningVpn) R.string.nlic_core_disconnect
                else R.string.nlic_control_connect)
        }
        findViewById<MaterialButton>(R.id.nlic_manage_profiles).isEnabled = !busy
        updateRealityAssistStatus()
    }

    /**
     * State is reported by the actual :native Xray runtime, not inferred
     * from the selected setting. This makes "smart enabled" distinct from
     * "fallback really attempted", "original worked", and "HTTPS verified".
     *
     * No secrets, server labels, IP addresses or subscription URI in the UI.
     */
    private fun updateRealityAssistStatus() {
        val line = findViewById<TextView>(R.id.nlic_reality_assist_status)
        if (connectionMode() == ConnectionMode.LOCAL ||
            !BuildConfig.NLIC_XRAY_INCLUDED) {
            line.visibility = View.GONE
            return
        }
        val snapshot = RealityAssistTrace.recent(applicationContext)
        val configured = prefs.getString("nlic_reality_tcp_utls", "profile")
        val message = when (snapshot?.event) {
            RealityAssistTrace.Event.SKIPPED_DISABLED ->
                "LIC-Assist не участвовал: использован исходный профиль."
            RealityAssistTrace.Event.SKIPPED_AUTO ->
                "LIC-Assist не участвовал: включён автоматический выбор серверов."
            RealityAssistTrace.Event.SKIPPED_UNSUPPORTED ->
                "LIC-Assist не применялся: выбранный сервер не VLESS + REALITY + TCP (RAW)."
            RealityAssistTrace.Event.STARTING_ORIGINAL ->
                "LIC-Assist: запускается проверенный вариант REALITY…"
            RealityAssistTrace.Event.CALIBRATING_ORIGINAL ->
                "LIC-Assist: сравниваем исходный REALITY по защищённому HTTPS…"
            RealityAssistTrace.Event.CALIBRATING_CHROME ->
                "LIC-Assist: сравниваем Chrome на том же сервере…"
            RealityAssistTrace.Event.CALIBRATING_FIREFOX ->
                "LIC-Assist: сравниваем Firefox на том же сервере…"
            RealityAssistTrace.Event.CALIBRATING_SAFARI ->
                "LIC-Assist: сравниваем Safari на том же сервере…"
            RealityAssistTrace.Event.CHECKING_ORIGINAL ->
                "LIC-Assist: проверяется HTTPS с исходным отпечатком…"
            RealityAssistTrace.Event.RETRYING_FIREFOX ->
                "Исходный отпечаток не прошёл HTTPS. Пробуем тот же сервер с Firefox…"
            RealityAssistTrace.Event.RETRYING_CHROME ->
                "Предыдущий отпечаток не прошёл HTTPS. Пробуем Chrome…"
            RealityAssistTrace.Event.RETRYING_SAFARI ->
                "Предыдущий отпечаток не прошёл HTTPS. Пробуем Safari…"
            RealityAssistTrace.Event.CHECKING_FIREFOX ->
                "Резервный Firefox: проверяем настоящий HTTPS через VPN…"
            RealityAssistTrace.Event.CHECKING_CHROME ->
                "Chrome: проверяем настоящий HTTPS через REALITY…"
            RealityAssistTrace.Event.CHECKING_SAFARI ->
                "Safari: проверяем настоящий HTTPS через REALITY…"
            RealityAssistTrace.Event.CHECKING_MANUAL_FIREFOX ->
                "Выбран Firefox: проверяем HTTPS через VPN…"
            RealityAssistTrace.Event.CHECKING_MANUAL_CHROME ->
                "Выбран Chrome: проверяем HTTPS через VPN…"
            RealityAssistTrace.Event.CHECKING_MANUAL_SAFARI ->
                "Выбран Safari: проверяем HTTPS через VPN…"
            RealityAssistTrace.Event.PASSED_ORIGINAL ->
                "LIC-Assist: исходный REALITY прошёл HTTPS. Он остался лучшим подтверждённым вариантом."
            RealityAssistTrace.Event.PASSED_ADAPTIVE_CHROME ->
                "LIC-Assist: выбран Chrome · HTTPS работает на этом сервере."
            RealityAssistTrace.Event.PASSED_ADAPTIVE_FIREFOX ->
                "LIC-Assist: выбран Firefox · HTTPS работает на этом сервере."
            RealityAssistTrace.Event.PASSED_ADAPTIVE_SAFARI ->
                "LIC-Assist: выбран Safari · HTTPS работает на этом сервере."
            RealityAssistTrace.Event.PASSED_FALLBACK_FIREFOX ->
                "LIC-Assist: резервный Firefox прошёл HTTPS. Исходный отпечаток не прошёл проверку."
            RealityAssistTrace.Event.PASSED_FALLBACK_CHROME ->
                "LIC-Assist: резервный Chrome прошёл HTTPS. Исходный отпечаток не прошёл проверку."
            RealityAssistTrace.Event.PASSED_MANUAL_FIREFOX ->
                "LIC-Assist: выбранный Firefox прошёл HTTPS. Это не доказывает обход DPI."
            RealityAssistTrace.Event.PASSED_MANUAL_CHROME ->
                "LIC-Assist: выбранный Chrome прошёл HTTPS. Это не доказывает обход DPI."
            RealityAssistTrace.Event.PASSED_MANUAL_SAFARI ->
                "LIC-Assist: выбранный Safari прошёл HTTPS. Это не доказывает обход DPI."
            RealityAssistTrace.Event.FAILED ->
                "LIC-Assist: проверенный HTTPS установить не удалось. См. код ошибки."
            null -> when {
                configured == "smart" && prefs.getBoolean("nlic_core_auto", false) ->
                    "Умный режим выбран, но при LIC-Auto резервный отпечаток не применяется."
                configured == "smart" ->
                    "Умный режим выбран. Его результат появится здесь после подключения."
                configured == "firefox" -> "Firefox выбран. Подключение ещё не проверено."
                configured == "chrome" -> "Chrome выбран. Подключение ещё не проверено."
                else -> "LIC-Assist выключен: используется исходный профиль."
            }
        }
        val running = appStatus.first == AppStatus.Running &&
            appStatus.second == Mode.VPN
        val inProgress = snapshot?.event in setOf(
            RealityAssistTrace.Event.STARTING_ORIGINAL,
            RealityAssistTrace.Event.CALIBRATING_ORIGINAL,
            RealityAssistTrace.Event.CALIBRATING_CHROME,
            RealityAssistTrace.Event.CALIBRATING_FIREFOX,
            RealityAssistTrace.Event.CALIBRATING_SAFARI,
            RealityAssistTrace.Event.CHECKING_ORIGINAL,
            RealityAssistTrace.Event.RETRYING_FIREFOX,
            RealityAssistTrace.Event.RETRYING_CHROME,
            RealityAssistTrace.Event.RETRYING_SAFARI,
            RealityAssistTrace.Event.CHECKING_FIREFOX,
            RealityAssistTrace.Event.CHECKING_CHROME,
            RealityAssistTrace.Event.CHECKING_SAFARI,
            RealityAssistTrace.Event.CHECKING_MANUAL_FIREFOX,
            RealityAssistTrace.Event.CHECKING_MANUAL_CHROME,
            RealityAssistTrace.Event.CHECKING_MANUAL_SAFARI
        )
        line.text = if (snapshot != null && !running && !busy && !inProgress)
            "Последняя попытка · $message" else message
        line.visibility = View.VISIBLE
    }

    /** Show a real same-node HTTPS A/B result, not a fabricated DPI score. */
    private fun showComparisonResult() {
        val resultView = findViewById<TextView>(R.id.nlic_reality_compare_result)
        val text = comparisonResult
        resultView.visibility = if (connectionMode() == ConnectionMode.SUBSCRIPTION &&
            !text.isNullOrBlank()) View.VISIBLE else View.GONE
        resultView.text = if (comparisonDetails != null)
            "${text.orEmpty()}  ·  Подробнее" else text.orEmpty()
        resultView.setOnClickListener(if (comparisonDetails != null) {
            View.OnClickListener { comparisonDetails?.let { showComparisonDialog(it) } }
        } else null)
    }

    private fun startRealityComparison(trials: Int) {
        if (busy || connectionMode() != ConnectionMode.SUBSCRIPTION) return
        // The button is disabled while the VPN is connected, but enforce the
        // preconditions again for stale clicks and concurrent status changes.
        if (appStatus.first == AppStatus.Running) {
            comparisonResult = getString(R.string.nlic_reality_compare_requires_disconnected)
            showComparisonResult()
            return
        }
        if (prefs.getBoolean("nlic_core_auto", false) ||
            prefs.getBoolean("nlic_core_bay", false)) {
            comparisonResult = getString(R.string.nlic_reality_compare_require_manual)
            showComparisonResult()
            return
        }
        val selected = readCatalog().let { catalog ->
            catalog.allNodes.firstOrNull { it.id == catalog.selectedNodeId }
        }
        val auth = LocalSocksAuth("nlic", "a".repeat(48))
        val baseline = selected?.let {
            XrayConfigCompiler.compile(it, auth) as? XrayConfigCompiler.Result.Compiled
        }
        if (baseline == null || RealityAssistPolicy.baselineFingerprint(baseline.json) == null) {
            comparisonResult = getString(R.string.nlic_reality_compare_unsupported)
            showComparisonResult()
            return
        }

        comparisonRunning = true
        busy = true
        comparisonResult = getString(R.string.nlic_reality_compare_in_progress)
        comparisonDetails = null
        render()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (!comparisonRunning) return
                try {
                    Messenger(binder).send(
                        Message.obtain(null, RealityCompareService.START).apply {
                            replyTo = comparisonReceiver
                            data = Bundle().apply {
                                putInt(RealityCompareService.TRIALS, trials)
                            }
                        }
                    )
                } catch (_: RemoteException) {
                    endRealityComparison(getString(R.string.nlic_reality_compare_failed))
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (comparisonRunning) {
                    endRealityComparison(getString(R.string.nlic_reality_compare_failed))
                }
            }
        }
        comparisonConnection = connection
        try {
            if (!bindService(
                    Intent(this, RealityCompareService::class.java),
                    connection, Context.BIND_AUTO_CREATE
                )) {
                endRealityComparison(getString(R.string.nlic_reality_compare_failed))
            }
        } catch (_: Exception) {
            endRealityComparison(getString(R.string.nlic_reality_compare_failed))
        }
    }

    private fun showComparisonDialog(details: String) {
        if (isFinishing || isDestroyed) return
        // Unlike a giant default message dialog, a constrained scroll view
        // keeps results readable at large Android font scales.
        val density = resources.displayMetrics.density
        val vertical = (15f * density).toInt()
        val horizontal = (24f * density).toInt()
        val body = TextView(this).apply {
            text = details
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(androidx.core.content.ContextCompat.getColor(
                this@SubscriptionLabActivity, R.color.nlic_text
            ))
            setPadding(horizontal, vertical, horizontal, vertical)
            setLineSpacing((3f * density), 1.0f)
        }
        val viewport = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val height = (resources.displayMetrics.heightPixels * 0.60f).toInt()
                super.onMeasure(widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
            }
        }.apply {
            addView(body)
            isFillViewport = false
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.nlic_reality_compare_title)
            .setView(viewport)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun endRealityComparison(
        outcome: String,
        compact: String? = null,
        showDialog: Boolean = false,
    ) {
        comparisonDetails = if (showDialog) outcome else null
        comparisonResult = compact ?: outcome
        comparisonRunning = false
        comparisonConnection?.let {
            runCatching { unbindService(it) }
        }
        comparisonConnection = null
        busy = false
        render()
        if (showDialog) showComparisonDialog(outcome)
    }

    private fun runOperation(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        render()
        lifecycleScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                latestMessage = getString(R.string.nlic_control_generic_failure)
                locallyVerified = false
            } finally {
                busy = false
                render()
            }
        }
    }
}
