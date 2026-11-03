package io.github.romanvht.byedpi.subscription

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import android.app.Activity
import android.net.VpnService
import androidx.activity.result.contract.ActivityResultContracts
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.BuildConfig
import io.github.romanvht.byedpi.utility.getPreferences
import io.github.romanvht.byedpi.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * Subscription manager and explicit control of the integrated VPN core.
 * TCP shortlist and authenticated outbound tests remain separate actions.
 */
class SubscriptionLabFragment : PreferenceFragmentCompat() {
    private var connecting = false
    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) connectCore()
        else Toast.makeText(requireContext(), R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        render()
    }

    private fun render() {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)
        preferenceScreen = screen

        val section = PreferenceCategory(context).apply {
            title = getString(R.string.nlic_subscriptions_title)
        }
        screen.addPreference(section)

        section.addPreference(Preference(context).apply {
            title = getString(R.string.nlic_subscriptions_phase)
            summary = getString(
                if (BuildConfig.NLIC_LIBBOX_INCLUDED) R.string.nlic_core_preview_warning
                else R.string.nlic_subscriptions_phase_desc
            )
            isSelectable = false
        })
        if (!SubscriptionVault.available()) {
            section.addPreference(Preference(context).apply {
                title = getString(R.string.nlic_subscriptions_unsupported)
                isSelectable = false
            })
            return
        }

        section.addPreference(Preference(context).apply {
            title = getString(R.string.nlic_subscriptions_add_url)
            summary = getString(R.string.nlic_subscriptions_url_hint)
            setOnPreferenceClickListener {
                showInputDialog(R.string.nlic_subscriptions_add_url, "https://") { input ->
                    importUrl(input)
                }
                true
            }
        })
        section.addPreference(Preference(context).apply {
            title = getString(R.string.nlic_subscriptions_add_link)
            summary = getString(R.string.nlic_subscriptions_add_link_hint)
            setOnPreferenceClickListener {
                showInputDialog(R.string.nlic_subscriptions_add_link, "") { input ->
                    importManual(input)
                }
                true
            }
        })

        val saved = runCatching { SubscriptionVault.read(context) }.getOrElse {
            section.addPreference(Preference(context).apply {
                title = getString(R.string.nlic_subscriptions_vault_error)
                summary = getString(R.string.nlic_subscriptions_vault_error_desc)
                isSelectable = false
            })
            return
        }

        val prefs = context.getPreferences()
        val selectedNode = saved.allNodes.firstOrNull { it.id == saved.selectedNodeId }
        val selectedBay = if (prefs.getBoolean("nlic_core_bay", false))
            SingBoxConfigCompiler.Bay.ON else SingBoxConfigCompiler.Bay.OFF
        val canCompile = selectedNode?.let {
            SingBoxConfigCompiler.compile(it, selectedBay) is SingBoxConfigCompiler.Result.Compiled
        } == true
        val byedpiVpnMode = prefs.getString("byedpi_mode", "vpn") == "vpn"
        val autoEnabled = prefs.getBoolean("nlic_core_auto", false)
        val autoPlan = SingBoxConfigCompiler.compileAuto(
            saved.allNodes,
            if (prefs.getBoolean("nlic_core_bay", false))
                SingBoxConfigCompiler.Bay.ON else SingBoxConfigCompiler.Bay.OFF,
        )
        val autoAvailable = autoPlan is SingBoxConfigCompiler.Result.Compiled

        section.addPreference(SwitchPreferenceCompat(context).apply {
            key = "nlic_core_mode"
            title = getString(R.string.nlic_core_toggle)
            summary = when {
                !BuildConfig.NLIC_LIBBOX_INCLUDED -> getString(R.string.nlic_core_missing)
                !byedpiVpnMode -> getString(R.string.nlic_core_requires_vpn)
                !(if (autoEnabled) autoAvailable else canCompile) ->
                    getString(R.string.nlic_core_choose_supported)
                else -> getString(R.string.nlic_core_reconnect)
            }
            isChecked = prefs.getBoolean(key, false)
            isEnabled = isChecked || (BuildConfig.NLIC_LIBBOX_INCLUDED && byedpiVpnMode &&
                (if (autoEnabled) autoAvailable else canCompile))
            setOnPreferenceChangeListener { _, newValue ->
                prefs.edit().putBoolean(key, newValue as Boolean).commit()
                render()
                true
            }
        })

        val bayEligible = if (autoEnabled) {
            saved.allNodes.any { node ->
                LICBay.plan(node).canWrapTcp &&
                    SingBoxConfigCompiler.compile(node) is
                        SingBoxConfigCompiler.Result.Compiled
            }
        } else selectedNode != null && LICBay.plan(selectedNode).canWrapTcp

        section.addPreference(SwitchPreferenceCompat(context).apply {
            key = "nlic_core_auto"
            title = getString(R.string.nlic_core_auto_toggle)
            summary = getString(R.string.nlic_core_auto_desc)
            isChecked = prefs.getBoolean(key, false)
            isEnabled = BuildConfig.NLIC_LIBBOX_INCLUDED && byedpiVpnMode &&
                saved.allNodes.isNotEmpty()
            setOnPreferenceChangeListener { _, newValue ->
                prefs.edit().putBoolean(key, newValue as Boolean).commit()
                render()
                true
            }
        })
        section.addPreference(SwitchPreferenceCompat(context).apply {
            key = "nlic_core_bay"
            title = getString(R.string.nlic_core_bay_toggle)
            summary = if (bayEligible) getString(R.string.nlic_core_bay_desc)
                else getString(R.string.nlic_core_bay_udp)
            isChecked = prefs.getBoolean(key, false)
            isEnabled = isChecked || (BuildConfig.NLIC_LIBBOX_INCLUDED && bayEligible &&
                prefs.getBoolean("nlic_core_mode", false))
            setOnPreferenceChangeListener { _, newValue ->
                prefs.edit().putBoolean(key, newValue as Boolean).commit()
                render()
                true
            }
        })

        section.addPreference(Preference(context).apply {
            title = getString(if (connecting) R.string.nlic_core_connecting else R.string.nlic_core_connect)
            summary = getString(R.string.nlic_core_connect_desc)
            isEnabled = !connecting && BuildConfig.NLIC_LIBBOX_INCLUDED && byedpiVpnMode &&
                (if (autoEnabled) autoAvailable else canCompile)
            setOnPreferenceClickListener {
                val permission = VpnService.prepare(context)
                if (permission == null) connectCore() else vpnPermission.launch(permission)
                true
            }
        })
        section.addPreference(Preference(context).apply {
            title = getString(R.string.nlic_core_disconnect)
            setOnPreferenceClickListener {
                ServiceManager.stop()
                true
            }
        })
        section.addPreference(Preference(context).apply {
            title = getString(R.string.nlic_core_profile_check)
            summary = if (if (autoEnabled) autoAvailable else canCompile)
                getString(R.string.nlic_core_profile_ready)
                else getString(R.string.nlic_core_profile_unsupported)
            isSelectable = false
        })

        val groups = saved.groups
        if (groups.isNotEmpty()) {
            section.addPreference(Preference(context).apply {
                title = getString(R.string.nlic_subscriptions_refresh)
                summary = getString(R.string.nlic_subscriptions_refresh_desc)
                setOnPreferenceClickListener {
                    refreshGroups(groups)
                    true
                }
            })
        }

        section.addPreference(Preference(context).apply {
            title = getString(R.string.nlic_auto_label)
            summary = getString(R.string.nlic_auto_tcp_only)
            isEnabled = saved.allNodes.isNotEmpty()
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
        section.addPreference(Preference(context).apply {
            title = getString(R.string.nlic_bay_label)
            summary = getString(if (BuildConfig.NLIC_LIBBOX_INCLUDED)
                R.string.nlic_core_bay_desc else R.string.nlic_bay_phase)
            isSelectable = false
        })
        if (saved.allNodes.isNotEmpty()) {
            val nodesCategory = PreferenceCategory(context).apply {
                title = getString(R.string.nlic_subscriptions_nodes, saved.allNodes.size)
            }
            screen.addPreference(nodesCategory)
            saved.allNodes.take(150).forEach { node ->
                val selected = node.id == saved.selectedNodeId
                nodesCategory.addPreference(Preference(context).apply {
                    title = (if (selected) "✓ " else "") + node.name
                    summary = "${node.protocol} · ${node.port} · " +
                        getString(if (BuildConfig.NLIC_LIBBOX_INCLUDED)
                            R.string.nlic_core_reconnect
                        else R.string.nlic_subscriptions_preview_only)
                    setOnPreferenceClickListener {
                        try {
                            SubscriptionVault.select(context, node.id)
                            render()
                        } catch (_: Exception) {
                            showError()
                        }
                        true
                    }
                })
            }
            section.addPreference(Preference(context).apply {
                title = getString(R.string.nlic_subscriptions_clear)
                setOnPreferenceClickListener {
                    AlertDialog.Builder(context)
                        .setTitle(R.string.nlic_subscriptions_clear)
                        .setMessage(R.string.nlic_subscriptions_clear_confirm)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            if (prefs.getBoolean("nlic_core_mode", false)) ServiceManager.stop()
                            prefs.edit().putBoolean("nlic_core_mode", false)
                                .putBoolean("nlic_core_auto", false)
                                .putBoolean("nlic_core_bay", false).commit()
                            SubscriptionVault.clear(context)
                            render()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                }
            })
        }
    }

    private fun connectCore() {
        if (connecting) return
        val app = requireContext().applicationContext
        app.getPreferences().edit().putBoolean("nlic_core_mode", true).commit()
        connecting = true
        render()
        lifecycleScope.launch {
            try {
                ServiceManager.waitStop()
                val connected = ServiceManager.waitStart(app, Mode.VPN)
                if (isAdded) Toast.makeText(requireContext(),
                    if (connected) R.string.nlic_core_connected else R.string.nlic_core_connect_failed,
                    Toast.LENGTH_LONG).show()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                if (isAdded) Toast.makeText(requireContext(),
                    R.string.nlic_core_connect_failed, Toast.LENGTH_LONG).show()
            } finally {
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
