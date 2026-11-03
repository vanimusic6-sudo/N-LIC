package io.github.romanvht.byedpi.activities

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import io.github.romanvht.byedpi.BuildConfig
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.data.AppStatus
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.services.appStatus
import io.github.romanvht.byedpi.subscription.LICAuto
import io.github.romanvht.byedpi.subscription.SingBoxConfigCompiler
import io.github.romanvht.byedpi.subscription.SubscriptionCatalog
import io.github.romanvht.byedpi.subscription.SubscriptionFetcher
import io.github.romanvht.byedpi.subscription.SubscriptionGroup
import io.github.romanvht.byedpi.subscription.SubscriptionImport
import io.github.romanvht.byedpi.subscription.SubscriptionNode
import io.github.romanvht.byedpi.subscription.SubscriptionVault
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
        findViewById<MaterialButton>(R.id.nlic_connect).setOnClickListener { requestConnection() }
        findViewById<MaterialButton>(R.id.nlic_disconnect).setOnClickListener { disconnectCore() }
        findViewById<MaterialButton>(R.id.nlic_expand_advanced).setOnClickListener {
            val panel = findViewById<View>(R.id.nlic_advanced_panel)
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<MaterialButton>(R.id.nlic_help).setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.nlic_ui_help)
                .setMessage(R.string.nlic_ui_help_steps)
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
    }

    override fun onResume() {
        super.onResume()
        if (::class.java != null) render()
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
            val parsed = SubscriptionImport.parse(body, hostLabel)
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
        val first = store.allNodes.firstOrNull {
            SingBoxConfigCompiler.compile(it) is SingBoxConfigCompiler.Result.Compiled
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
        for (group in groups.take(30)) {
            val updated = runCatching {
                withContext(Dispatchers.IO) {
                    val nodes = SubscriptionImport.parse(
                        SubscriptionFetcher.fetch(group.url), group.title
                    )
                    if (nodes.isNotEmpty()) {
                        SubscriptionVault.updateGroup(applicationContext,
                            group.copy(nodes = nodes, refreshedAt = System.currentTimeMillis()))
                    }
                    nodes.size
                }
            }.getOrDefault(0)
            count += updated
        }
        latestMessage = getString(R.string.nlic_subscriptions_refreshed, count)
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
        if (!BuildConfig.NLIC_LIBBOX_INCLUDED) {
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
        if (!BuildConfig.NLIC_LIBBOX_INCLUDED) return false
        val bay = if (prefs.getBoolean("nlic_core_bay", false))
            SingBoxConfigCompiler.Bay.ON else SingBoxConfigCompiler.Bay.OFF
        return if (prefs.getBoolean("nlic_core_auto", false)) {
            SingBoxConfigCompiler.compileAuto(catalog.allNodes, bay)
                is SingBoxConfigCompiler.Result.Compiled
        } else {
            val selected = catalog.allNodes.firstOrNull { it.id == catalog.selectedNodeId }
                ?: return false
            SingBoxConfigCompiler.compile(selected, bay) is SingBoxConfigCompiler.Result.Compiled
        }
    }

    private fun render() {
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
        details.text = when {
            busy -> getString(R.string.nlic_control_working)
            latestMessage != null -> latestMessage
            !BuildConfig.NLIC_LIBBOX_INCLUDED -> getString(R.string.nlic_core_missing)
            all.isEmpty() -> getString(R.string.nlic_control_start_hint)
            runningVpn && !locallyVerified -> getString(R.string.nlic_control_android_only)
            runningVpn -> getString(R.string.nlic_control_verified)
            else -> getString(R.string.nlic_control_status_hint)
        }
        val failure = ServiceManager.lastCoreFailureCode
        stage.text = when {
            runningVpn && locallyVerified -> getString(R.string.nlic_control_https_ok)
            failure != null -> getString(R.string.nlic_control_stage_failure, failure)
            else -> getString(R.string.nlic_control_preview_badge)
        }
        findViewById<View>(R.id.nlic_progress).visibility =
            if (busy) View.VISIBLE else View.GONE

        val picker = findViewById<MaterialAutoCompleteTextView>(R.id.nlic_server_picker)
        val labels = all.take(150).map {
            it.name + "  ·  " + it.protocol
        }
        picker.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels))
        picker.setText(
            if (auto) getString(R.string.nlic_control_auto_selected)
            else selected?.let { it.name + "  ·  " + it.protocol }
                ?: getString(R.string.nlic_control_select_server), false
        )
        picker.setOnItemClickListener { _, _, position, _ ->
            val node = all.getOrNull(position) ?: return@setOnItemClickListener
            runCatching { SubscriptionVault.select(applicationContext, node.id) }
                .onSuccess {
                    // A UDP node cannot use ByeDPI's TCP-only upstream detour.
                    if (!auto && bay && SingBoxConfigCompiler.compile(
                            node, SingBoxConfigCompiler.Bay.ON
                        ) !is SingBoxConfigCompiler.Result.Compiled
                    ) {
                        prefs.edit().putBoolean("nlic_core_bay", false).commit()
                    }
                    latestMessage = null
                    locallyVerified = false
                    render()
                }.onFailure { toast(R.string.nlic_subscriptions_error) }
        }
        picker.isEnabled = !busy && all.isNotEmpty()
        findViewById<TextView>(R.id.nlic_server_hint).text = when {
            all.isEmpty() -> getString(R.string.nlic_control_start_hint)
            auto -> getString(R.string.nlic_core_auto_desc)
            selected == null -> getString(R.string.nlic_ui_step_choose)
            SingBoxConfigCompiler.compile(selected) is SingBoxConfigCompiler.Result.Compiled ->
                getString(R.string.nlic_control_selected_ok)
            else -> getString(R.string.nlic_control_unsupported)
        }

        val autoSwitch = findViewById<MaterialSwitch>(R.id.nlic_auto_switch)
        autoSwitch.setOnCheckedChangeListener(null)
        autoSwitch.isChecked = auto
        autoSwitch.isEnabled = !busy && BuildConfig.NLIC_LIBBOX_INCLUDED && all.isNotEmpty()
        autoSwitch.setOnCheckedChangeListener { _, value ->
            prefs.edit().putBoolean("nlic_core_auto", value).commit()
            latestMessage = null
            render()
        }
        val bayEligible = if (auto) all.any {
            SingBoxConfigCompiler.compile(it, SingBoxConfigCompiler.Bay.ON) is
                SingBoxConfigCompiler.Result.Compiled
        } else selected?.let {
            SingBoxConfigCompiler.compile(it, SingBoxConfigCompiler.Bay.ON) is
                SingBoxConfigCompiler.Result.Compiled
        } == true
        val baySwitch = findViewById<MaterialSwitch>(R.id.nlic_bay_switch)
        baySwitch.setOnCheckedChangeListener(null)
        baySwitch.isChecked = bay
        baySwitch.isEnabled = !busy && BuildConfig.NLIC_LIBBOX_INCLUDED && (bay || bayEligible)
        baySwitch.setOnCheckedChangeListener { _, value ->
            prefs.edit().putBoolean("nlic_core_bay", value).commit()
            latestMessage = null
            render()
        }
        findViewById<TextView>(R.id.nlic_bay_hint).setText(
            if (bayEligible) R.string.nlic_control_bay_desc else R.string.nlic_core_bay_udp
        )

        findViewById<MaterialButton>(R.id.nlic_connect).apply {
            isEnabled = !busy && canConnect(catalog)
            setText(if (runningVpn) R.string.nlic_control_apply else R.string.nlic_control_connect)
        }
        findViewById<MaterialButton>(R.id.nlic_disconnect).apply {
            visibility = if (runningVpn) View.VISIBLE else View.GONE
            isEnabled = !busy
        }
        listOf(R.id.nlic_add_subscription, R.id.nlic_add_link,
            R.id.nlic_refresh, R.id.nlic_tcp_check, R.id.nlic_legacy_mode,
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
