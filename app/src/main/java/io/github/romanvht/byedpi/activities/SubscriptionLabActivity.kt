package io.github.romanvht.byedpi.activities

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
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
import io.github.romanvht.byedpi.services.appStatus
import io.github.romanvht.byedpi.subscription.LICAuto
import io.github.romanvht.byedpi.subscription.SingBoxConfigCompiler
import io.github.romanvht.byedpi.subscription.XrayConfigCompiler
import io.github.romanvht.byedpi.subscription.LocalSocksAuth
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
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.nlic_control_title)

        findViewById<MaterialButton>(R.id.nlic_add_subscription).setOnClickListener {
            showImportDialog(subscription = true)
        }
        findViewById<MaterialButton>(R.id.nlic_add_link).setOnClickListener {
            showImportDialog(subscription = false)
        }
        findViewById<MaterialButton>(R.id.nlic_add_file).setOnClickListener {
            if (!busy) filePicker.launch(arrayOf("*/*"))
        }
        findViewById<MaterialButton>(R.id.nlic_refresh_now).setOnClickListener {
            refreshSubscriptions()
        }
        findViewById<MaterialButton>(R.id.nlic_connect).setOnClickListener { requestConnection() }
        findViewById<MaterialButton>(R.id.nlic_disconnect).setOnClickListener { disconnectCore() }
        findViewById<MaterialButton>(R.id.nlic_expand_advanced).setOnClickListener {
            val panel = findViewById<View>(R.id.nlic_advanced_panel)
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<MaterialButton>(R.id.nlic_privacy).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.nlic_privacy_title)
                .setMessage(R.string.nlic_privacy_confirmation)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runCatching {
                        startActivity(android.content.Intent("android.settings.VPN_SETTINGS"))
                    }.onFailure {
                        toast(R.string.nlic_privacy_unavailable)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        findViewById<MaterialButton>(R.id.nlic_help).setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.nlic_ui_help)
                .setMessage(if (BuildConfig.NLIC_XRAY_INCLUDED) R.string.nlic_ui_help_steps_xray
                    else R.string.nlic_ui_help_steps)
                .setPositiveButton(android.R.string.ok, null).show()
        }
        findViewById<MaterialButton>(R.id.nlic_refresh).setOnClickListener { refreshSubscriptions() }
        findViewById<MaterialButton>(R.id.nlic_tcp_check).setOnClickListener { checkTcpEndpoints() }
        findViewById<MaterialButton>(R.id.nlic_legacy_mode).setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.nlic_ui_legacy_action)
                .setMessage(R.string.nlic_ui_legacy_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ -> disconnectCore() }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        findViewById<MaterialButton>(R.id.nlic_clear).setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.nlic_subscriptions_clear)
                .setMessage(R.string.nlic_subscriptions_clear_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runOperation {
                        ServiceManager.waitStop()
                        prefs.edit().putBoolean("nlic_core_mode", false)
                            .putBoolean("nlic_core_auto", false)
                            .putBoolean("nlic_core_bay", false).commit()
                        withContext(Dispatchers.IO) { SubscriptionVault.clear(applicationContext) }
                        locallyVerified = false
                        latestMessage = null
                    }
                }
                .setNegativeButton(android.R.string.cancel, null).show()
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
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
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

    private fun importSubscription(url: String) = runOperation {
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

    private fun refreshSubscriptions() = runOperation {
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
        if (!BuildConfig.NLIC_LIBBOX_INCLUDED && !BuildConfig.NLIC_XRAY_INCLUDED) {
            toast(R.string.nlic_core_missing)
            return
        }
        if (!canConnect(readCatalog())) {
            toast(R.string.nlic_core_choose_supported)
            return
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
            prefs.edit().putBoolean("nlic_core_mode", true).commit()
            var success = false
            try {
                success = ServiceManager.waitStart(applicationContext, Mode.VPN)
                locallyVerified = success
                if (success) latestMessage = getString(R.string.nlic_control_verified)
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
        findViewById<TextView>(R.id.nlic_supported_protocols).setText(
            if (BuildConfig.NLIC_XRAY_INCLUDED) R.string.nlic_home_supported_xray_protocols
            else R.string.nlic_home_supported_protocols
        )
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
            busy -> R.string.nlic_core_connecting
            runningVpn -> R.string.nlic_control_running
            else -> R.string.nlic_control_disconnected
        })
        val failure = ServiceManager.lastCoreFailureCode
        details.text = when {
            busy -> getString(R.string.nlic_control_working)
            failure != null && !runningVpn -> failureAdvice(failure)
            latestMessage != null -> latestMessage
            !BuildConfig.NLIC_LIBBOX_INCLUDED && !BuildConfig.NLIC_XRAY_INCLUDED ->
                getString(R.string.nlic_core_missing)
            all.isEmpty() -> getString(R.string.nlic_control_start_hint)
            runningVpn && !locallyVerified -> getString(R.string.nlic_control_android_only)
            runningVpn -> getString(R.string.nlic_control_verified)
            else -> getString(R.string.nlic_control_status_hint)
        }
        stage.text = when {
            runningVpn && locallyVerified -> getString(R.string.nlic_control_https_ok)
            failure != null -> getString(R.string.nlic_control_stage_failure, failure)
            else -> getString(R.string.nlic_control_preview_badge)
        }
        findViewById<View>(R.id.nlic_progress).visibility =
            if (busy) View.VISIBLE else View.GONE

        val choices = all.take(150)
        val labels = choices.map { node -> node.name + "  ·  " + node.protocol }
        findViewById<MaterialButton>(R.id.nlic_server_picker_button).apply {
            text = if (auto) getString(R.string.nlic_control_auto_selected)
                else selected?.let { it.name + "  ·  " + it.protocol }
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
        val bayHint = if (BuildConfig.NLIC_XRAY_INCLUDED) {
            getString(R.string.nlic_xray_bay_hint)
        } else if (bayEligible) getString(R.string.nlic_control_bay_desc)
            else getString(R.string.nlic_core_bay_udp)
        findViewById<TextView>(R.id.nlic_bay_hint).text =
            if (bay) bayHint + "\n" + getString(R.string.nlic_control_bay_risk) else bayHint

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
            isEnabled = !busy && canConnect(catalog)
            setText(if (runningVpn) R.string.nlic_control_apply else R.string.nlic_control_connect)
        }
        findViewById<MaterialButton>(R.id.nlic_disconnect).apply {
            visibility = if (runningVpn) View.VISIBLE else View.GONE
            isEnabled = !busy
        }
        listOf(R.id.nlic_add_subscription, R.id.nlic_add_link,
            R.id.nlic_add_file, R.id.nlic_refresh_now, R.id.nlic_refresh, R.id.nlic_tcp_check, R.id.nlic_legacy_mode,
            R.id.nlic_clear).forEach { id ->
            findViewById<MaterialButton>(id).isEnabled = !busy
        }
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
