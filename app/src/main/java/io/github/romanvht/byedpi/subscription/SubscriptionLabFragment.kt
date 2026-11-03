package io.github.romanvht.byedpi.subscription

import android.app.Activity
import android.content.Context
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import io.github.romanvht.byedpi.BuildConfig
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.data.AppStatus
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.services.appStatus
import io.github.romanvht.byedpi.utility.getPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * LIC-Core control centre. Status distinguishes a running Android VPN from
 * confirmed encrypted remote readiness (only the start action verifies that).
 * Subscription data stays inside Android Keystore, never in preference keys.
 */
class SubscriptionLabFragment : PreferenceFragmentCompat() {
    private var connecting = false
    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) connectCore()
        else if (isAdded) Toast.makeText(requireContext(),
            R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        render()
    }

    override fun onResume() {
        super.onResume()
        if (preferenceScreen != null) render()
    }

    private fun render() {
        if (!isAdded) return
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)
        preferenceScreen = screen

        if (!SubscriptionVault.available()) {
            screen.addPreference(Preference(context).apply {
                title = getString(R.string.nlic_subscriptions_unsupported)
                isSelectable = false
            })
            return
        }

        val saved = runCatching { SubscriptionVault.read(context) }.getOrElse {
            screen.addPreference(Preference(context).apply {
                title = getString(R.string.nlic_subscriptions_vault_error)
                summary = getString(R.string.nlic_subscriptions_vault_error_desc)
                isSelectable = false
            })
            return
        }
        val prefs = context.getPreferences()
        val allNodes = saved.allNodes
        val selectedNode = allNodes.firstOrNull { it.id == saved.selectedNodeId }
        val autoEnabled = prefs.getBoolean("nlic_core_auto", false)
        val bayEnabled = prefs.getBoolean("nlic_core_bay", false)
        val bay = if (bayEnabled) SingBoxConfigCompiler.Bay.ON
            else SingBoxConfigCompiler.Bay.OFF
        val byedpiVpnMode = prefs.getString("byedpi_mode", "vpn") == "vpn"
        val canCompile = selectedNode?.let {
            SingBoxConfigCompiler.compile(it, bay) is SingBoxConfigCompiler.Result.Compiled
        } == true
        val autoAvailable = SingBoxConfigCompiler.compileAuto(allNodes, bay) is
            SingBoxConfigCompiler.Result.Compiled
        val canConnect = BuildConfig.NLIC_LIBBOX_INCLUDED && byedpiVpnMode &&
            (if (autoEnabled) autoAvailable else canCompile)
        val runningVpn = appStatus.first == AppStatus.Running && appStatus.second == Mode.VPN
        // Do not infer remote VPN success solely from Android's foreground status.
        val possibleCoreRunning = runningVpn && prefs.getBoolean("nlic_core_mode", false)

        screen.addPreference(NlicDashboardPreference(context).apply {
            heading = getString(when {
                connecting -> R.string.nlic_core_connecting
                possibleCoreRunning -> R.string.nlic_ui_running
                runningVpn -> R.string.nlic_ui_legacy_running
                else -> R.string.nlic_ui_disconnected
            })
            details = when {
                !BuildConfig.NLIC_LIBBOX_INCLUDED -> getString(R.string.nlic_core_missing)
                autoEnabled -> getString(R.string.nlic_core_auto_toggle)
                selectedNode != null -> selectedNode.name + " · " + selectedNode.protocol
                else -> getString(R.string.nlic_ui_no_server)
            }
            hint = getString(when {
                !BuildConfig.NLIC_LIBBOX_INCLUDED -> R.string.nlic_core_missing
                !byedpiVpnMode -> R.string.nlic_core_requires_vpn
                allNodes.isEmpty() -> R.string.nlic_ui_step_import
                !autoEnabled && selectedNode == null -> R.string.nlic_ui_step_choose
                !canConnect -> R.string.nlic_core_profile_unsupported
                possibleCoreRunning -> R.string.nlic_ui_running_hint
                else -> R.string.nlic_ui_step_connect
            })
            this.connecting = this@SubscriptionLabFragment.connecting
            connectAllowed = canConnect
            showDisconnect = runningVpn
            onConnect = {
                val permission = VpnService.prepare(context)
                if (permission == null) connectCore() else vpnPermission.launch(permission)
            }
            onDisconnect = { disconnectCore() }
            onHelp = {
                AlertDialog.Builder(context)
                    .setTitle(R.string.nlic_ui_help)
                    .setMessage(R.string.nlic_ui_help_steps)
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        })

        val imports = PreferenceCategory(context).apply {
            title = getString(R.string.nlic_ui_import_section)
        }
        screen.addPreference(imports)
        imports.addPreference(Preference(context).apply {
            title = getString(R.string.nlic_subscriptions_add_url)
            summary = getString(R.string.nlic_subscriptions_url_hint)
            setOnPreferenceClickListener {
                showInputDialog(R.string.nlic_subscriptions_add_url, "https://") { input ->
                    importUrl(input)
                }
                true
            }
        })
        imports.addPreference(Preference(context).apply {
            title = getString(R.string.nlic_subscriptions_add_link)
            summary = getString(R.string.nlic_subscriptions_add_link_hint)
            setOnPreferenceClickListener {
                showInputDialog(R.string.nlic_subscriptions_add_link, "") { input ->
                    importManual(input)
                }
                true
            }
        })
        if (saved.groups.isNotEmpty()) {
            imports.addPreference(Preference(context).apply {
                title = getString(R.string.nlic_subscriptions_refresh)
                summary = getString(R.string.nlic_subscriptions_refresh_desc)
                setOnPreferenceClickListener {
                    refreshGroups(saved.groups)
                    true
                }
            })
        }

        val servers = PreferenceCategory(context).apply {
            title = getString(R.string.nlic_subscriptions_nodes, allNodes.size)
        }
        screen.addPreference(servers)
        if (allNodes.isEmpty()) {
            servers.addPreference(Preference(context).apply {
                title = getString(R.string.nlic_ui_no_server)
                summary = getString(R.string.nlic_ui_step_import)
                isSelectable = false
            })
        } else {
            allNodes.take(150).forEach { node ->
                val selected = node.id == saved.selectedNodeId
                val compatible = SingBoxConfigCompiler.compile(node, bay) is
                    SingBoxConfigCompiler.Result.Compiled
                servers.addPreference(Preference(context).apply {
                    title = (if (selected) "●  " else "○  ") + node.name
                    summary = node.protocol + "  ·  " + getString(
                        when {
                            !compatible -> R.string.nlic_ui_unsupported
                            selected -> R.string.nlic_ui_selected
                            else -> R.string.nlic_ui_available
                        }
                    )
                    isEnabled = !connecting
                    setOnPreferenceClickListener {
                        runCatching { SubscriptionVault.select(context, node.id) }
                            .onSuccess { render() }
                            .onFailure { showError() }
                        true
                    }
                })
            }
        }

        val enhancements = PreferenceCategory(context).apply {
            title = getString(R.string.nlic_ui_enhancements_section)
        }
        screen.addPreference(enhancements)
        enhancements.addPreference(SwitchPreferenceCompat(context).apply {
            key = "nlic_core_auto"
            title = getString(R.string.nlic_core_auto_toggle)
            summary = getString(R.string.nlic_core_auto_desc)
            isChecked = autoEnabled
            isEnabled = !connecting && BuildConfig.NLIC_LIBBOX_INCLUDED && byedpiVpnMode &&
                allNodes.isNotEmpty()
            setOnPreferenceChangeListener { _, value ->
                prefs.edit().putBoolean(key, value as Boolean).commit()
                render()
                true
            }
        })

        val bayEligible = if (autoEnabled) {
            allNodes.any { node ->
                LICBay.plan(node).canWrapTcp &&
                    SingBoxConfigCompiler.compile(node) is SingBoxConfigCompiler.Result.Compiled
            }
        } else selectedNode != null && LICBay.plan(selectedNode).canWrapTcp &&
            SingBoxConfigCompiler.compile(selectedNode) is SingBoxConfigCompiler.Result.Compiled

        enhancements.addPreference(SwitchPreferenceCompat(context).apply {
            key = "nlic_core_bay"
            title = getString(R.string.nlic_core_bay_toggle)
            summary = getString(if (bayEligible) R.string.nlic_core_bay_desc
                else R.string.nlic_core_bay_udp)
            isChecked = bayEnabled
            // Always allow disabling an incompatible previously saved choice.
            isEnabled = !connecting && (bayEnabled ||
                (BuildConfig.NLIC_LIBBOX_INCLUDED && bayEligible && byedpiVpnMode))
            setOnPreferenceChangeListener { _, value ->
                prefs.edit().putBoolean(key, value as Boolean).commit()
                render()
                true
            }
        })
        enhancements.addPreference(Preference(context).apply {
            title = getString(R.string.nlic_auto_label)
            summary = getString(R.string.nlic_auto_tcp_only)
            isEnabled = !connecting && allNodes.isNotEmpty()
            setOnPreferenceClickListener {
                AlertDialog.Builder(context)
                    .setTitle(R.string.nlic_auto_label)
                    .setMessage(R.string.nlic_auto_consent)
                    .setPositiveButton(android.R.string.ok) { _, _ -> runLICAuto() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        })

        val advanced = PreferenceCategory(context).apply {
            title = getString(R.string.nlic_ui_advanced_section)
        }
        screen.addPreference(advanced)
        advanced.addPreference(Preference(context).apply {
            title = getString(R.string.nlic_core_profile_check)
            summary = getString(if (if (autoEnabled) autoAvailable else canCompile)
                R.string.nlic_core_profile_ready else R.string.nlic_core_profile_unsupported)
            isSelectable = false
        })
        advanced.addPreference(Preference(context).apply {
            title = getString(R.string.nlic_ui_legacy_action)
            summary = getString(R.string.nlic_ui_legacy_desc)
            isEnabled = !connecting
            setOnPreferenceClickListener {
                AlertDialog.Builder(context)
                    .setTitle(R.string.nlic_ui_legacy_action)
                    .setMessage(R.string.nlic_ui_legacy_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        lifecycleScope.launch {
                            ServiceManager.waitStop()
                            prefs.edit().putBoolean("nlic_core_mode", false).commit()
                            if (isAdded) render()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
                true
            }
        })
        if (allNodes.isNotEmpty() || saved.groups.isNotEmpty()) {
            advanced.addPreference(Preference(context).apply {
                title = getString(R.string.nlic_subscriptions_clear)
                isEnabled = !connecting
                setOnPreferenceClickListener {
                    AlertDialog.Builder(context)
                        .setTitle(R.string.nlic_subscriptions_clear)
                        .setMessage(R.string.nlic_subscriptions_clear_confirm)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            lifecycleScope.launch {
                                ServiceManager.waitStop()
                                prefs.edit().putBoolean("nlic_core_mode", false)
                                    .putBoolean("nlic_core_auto", false)
                                    .putBoolean("nlic_core_bay", false).commit()
                                runCatching { SubscriptionVault.clear(context) }
                                    .onFailure { showError() }
                                if (isAdded) render()
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null).show()
                    true
                }
            })
        }
        advanced.addPreference(Preference(context).apply {
            title = getString(R.string.nlic_subscriptions_phase)
            summary = getString(
                if (BuildConfig.NLIC_LIBBOX_INCLUDED) R.string.nlic_core_preview_warning
                else R.string.nlic_subscriptions_phase_desc)
            isSelectable = false
        })
    }

    private fun disconnectCore() {
        if (connecting) return
        connecting = true
        render()
        lifecycleScope.launch {
            try {
                ServiceManager.waitStop()
                // A future manually selected node must never silently launch
                // the experimental core from the app's legacy main screen.
                requireContext().getPreferences().edit()
                    .putBoolean("nlic_core_mode", false).commit()
            } finally {
                connecting = false
                if (isAdded) render()
            }
        }
    }

    private fun connectCore() {
        if (connecting) return
        val app = requireContext().applicationContext
        app.getPreferences().edit().putBoolean("nlic_core_mode", true).commit()
        connecting = true
        render()
        lifecycleScope.launch {
            var connected = false
            var cancelled = false
            try {
                ServiceManager.waitStop()
                connected = ServiceManager.waitStart(app, Mode.VPN)
                if (isAdded) Toast.makeText(requireContext(),
                    if (connected) R.string.nlic_core_connected else R.string.nlic_core_connect_failed,
                    Toast.LENGTH_LONG).show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            } catch (_: Exception) {
                if (isAdded) Toast.makeText(requireContext(),
                    R.string.nlic_core_connect_failed, Toast.LENGTH_LONG).show()
            } finally {
                // A failed start must not leave a persistent "Core enabled"
                // preference that breaks every subsequent legacy VPN startup.
                // On lifecycle cancellation, connection state is uncertain:
                // leave the engine in charge rather than toggling under it.
                if (!connected && !cancelled) {
                    app.getPreferences().edit().putBoolean("nlic_core_mode", false).commit()
                }
                connecting = false
                if (isAdded) render()
            }
        }
    }

    private fun showInputDialog(titleId: Int, initial: String, submit: (String) -> Unit) {
        val context = requireContext()
        val entry = EditText(context).apply {
            setSingleLine(false)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            setText(initial)
            setSelection(text.length)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val contentContainer = android.widget.FrameLayout(context).apply {
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(entry, android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(titleId)
            .setView(contentContainer)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = entry.text.toString().trim()
                if (value.isNotEmpty()) submit(value)
                entry.text.clear()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
    }

    private fun importManual(text: String) {
        val context = requireContext()
        val parsed = runCatching { SubscriptionImport.parse(text) }.getOrElse {
            showError(); return
        }
        if (parsed.isEmpty()) { showError(); return }
        runCatching {
            SubscriptionVault.addManual(context, parsed)
        }.onSuccess {
            Toast.makeText(context,
                getString(R.string.nlic_subscriptions_imported, it), Toast.LENGTH_SHORT).show()
            render()
        }.onFailure { showError() }
    }

    private fun importUrl(url: String) {
        if (!SubscriptionFetcher.validSubscriptionUrl(url)) { showError(); return }
        val app = requireContext().applicationContext
        val progress = showProgress()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                val body = SubscriptionFetcher.fetch(url)
                val title = URI(url).host ?: "Subscription"
                val nodes = SubscriptionImport.parse(body, title)
                require(nodes.isNotEmpty()) { "No supported nodes" }
                withContext(Dispatchers.IO) {
                    SubscriptionVault.updateGroup(
                        app, SubscriptionGroup(title, url, nodes, System.currentTimeMillis())
                    )
                }
                nodes.size
            }
            progress.dismiss()
            if (!isAdded) return@launch
            result.onSuccess {
                Toast.makeText(requireContext(),
                    getString(R.string.nlic_subscriptions_imported, it), Toast.LENGTH_SHORT).show()
                render()
            }.onFailure { showError() }
        }
    }

    private fun refreshGroups(groups: List<SubscriptionGroup>) {
        val app = requireContext().applicationContext
        val progress = showProgress()
        viewLifecycleOwner.lifecycleScope.launch {
            var updated = 0
            for (group in groups.take(30)) {
                val attempt = runCatching {
                    val body = SubscriptionFetcher.fetch(group.url)
                    val nodes = SubscriptionImport.parse(body, group.title)
                    if (nodes.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            SubscriptionVault.updateGroup(
                                app, group.copy(nodes = nodes, refreshedAt = System.currentTimeMillis())
                            )
                        }
                        nodes.size
                    } else 0
                }
                if (attempt.isSuccess) updated += attempt.getOrDefault(0)
            }
            progress.dismiss()
            if (!isAdded) return@launch
            Toast.makeText(requireContext(),
                getString(R.string.nlic_subscriptions_refreshed, updated), Toast.LENGTH_SHORT).show()
            render()
        }
    }

    private fun runLICAuto() {
        val app = requireContext().applicationContext
        val progress = showProgress()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                val catalog = withContext(Dispatchers.IO) { SubscriptionVault.read(app) }
                LICAuto.check(catalog.allNodes)
            }
            progress.dismiss()
            if (!isAdded) return@launch
            result.onSuccess { checks ->
                val labels = checks.map {
                    val ms = it.medianConnectMs?.let { value -> "$value ms" } ?: "—"
                    "${it.node.name} · $ms · ${it.successes}/${it.attempts} TCP"
                }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.nlic_auto_results)
                    .setItems(labels) { _, index ->
                        val result = checks[index]
                        if (result.successes == result.attempts && result.attempts > 0) {
                            SubscriptionVault.select(requireContext(), result.node.id)
                            render()
                        } else {
                            Toast.makeText(requireContext(),
                                R.string.nlic_auto_ineligible, Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }.onFailure { showError() }
        }
    }

    private fun showProgress(): AlertDialog {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.nlic_subscriptions_title)
            .setMessage(R.string.nlic_subscriptions_working)
            .setCancelable(false)
            .create()
        dialog.show()
        return dialog
    }

    private fun showError() {
        if (isAdded) Toast.makeText(requireContext(),
            R.string.nlic_subscriptions_error, Toast.LENGTH_LONG).show()
    }
}
