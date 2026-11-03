package io.github.romanvht.byedpi.activities

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.BuildConfig
import io.github.romanvht.byedpi.data.*
import io.github.romanvht.byedpi.databinding.ActivityMainBinding
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.services.TestService
import io.github.romanvht.byedpi.services.appStatus
import io.github.romanvht.byedpi.subscription.SubscriptionVault
import io.github.romanvht.byedpi.utility.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import kotlin.system.exitProcess

class MainActivity : BaseActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var historyUtils: HistoryUtils

    companion object {
        private val TAG: String = MainActivity::class.java.simpleName

        private fun collectLogs(): String? {
            return try {
                // Diagnostic export is initiated by the user. Never dump
                // unrelated apps' device-wide logs; keep recent app-UID
                // records from both the UI and separate native process.
                val selector = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "--uid=${android.os.Process.myUid()}"
                } else {
                    "--pid=${android.os.Process.myPid()}"
                }
                val process = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-d", "-t", "2500", selector, "*:D")
                )
                val output = process.inputStream.bufferedReader().use { it.readText() }
                if (process.waitFor() != 0) null else output
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to collect logs", exception)
                null
            }
        }
    }

    private val vpnRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                ServiceManager.start(this, Mode.VPN)
            } else {
                Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
                updateStatus()
            }
        }

    private val logsRegister = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        ::handleLogsFileResult
    )

    private fun handleLogsFileResult(result: ActivityResult) {
        if (result.resultCode != RESULT_OK) return
        val data = result.data ?: return
        val uri = data.data
        val path = data.getStringExtra(FileActivity.EXTRA_PATH)
        val file = if (path == null) null else File(path)
        if (uri == null && file == null) return

        lifecycleScope.launch(Dispatchers.IO) {
            val logs = collectLogs()
            if (logs == null) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, R.string.logs_failed, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            try {
                val outputStream = when {
                    uri != null -> contentResolver.openOutputStream(uri)
                    file != null -> file.outputStream()
                    else -> null
                }
                if (outputStream == null) {
                    Log.e(TAG, "Failed to open output stream")
                } else {
                    outputStream.use { stream ->
                        stream.write(logs.toByteArray())
                    }
                }
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to save logs", exception)
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received intent: ${intent?.action}")

            if (intent == null) {
                Log.w(TAG, "Received null intent")
                return
            }

            val senderOrd = intent.getIntExtra(SENDER, -1)
            val sender = Sender.entries.getOrNull(senderOrd)
            if (sender == null) {
                Log.w(TAG, "Received intent with unknown sender: $senderOrd")
                return
            }

            when (val action = intent.action) {
                STARTED_BROADCAST,
                STOPPED_BROADCAST -> updateStatus()

                FAILED_BROADCAST -> {
                    Toast.makeText(
                        context,
                        getString(R.string.failed_to_start, sender.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                    updateStatus()
                }

                else -> Log.w(TAG, "Unknown action: $action")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceManager.refresh(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupToolbar()

        historyUtils = HistoryUtils(this)

        val intentFilter = IntentFilter().apply {
            addAction(STARTED_BROADCAST)
            addAction(STOPPED_BROADCAST)
            addAction(FAILED_BROADCAST)
        }

        // Internal state events must not be spoofable by third-party apps.
        ContextCompat.registerReceiver(
            this, receiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        binding.statusButtonCard.setOnClickListener {
            binding.statusButtonCard.isClickable = false

            val (status, _) = appStatus
            when (status) {
                AppStatus.Halted -> {
                    if (BuildConfig.NLIC_LIBBOX_INCLUDED) {
                        startActivity(Intent(this, SubscriptionLabActivity::class.java).apply {
                            putExtra("nlic_quick_connect", true)
                        })
                    } else start()
                }
                AppStatus.Running -> stop()
            }

            binding.statusButtonCard.postDelayed({
                binding.statusButtonCard.isClickable = true
            }, 1000)
        }

        binding.statusButtonCard.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.statusButtonCard.strokeWidth = 10
                binding.statusButtonCard.strokeColor = android.graphics.Color.argb(100, 0, 0, 0)
            } else {
                binding.statusButtonCard.strokeWidth = 0
            }
        }

        // Some resource configurations use a compact alternative home layout.
        // ViewBinding therefore exposes this optional button as nullable.
        binding.nlicCoreButton?.apply {
            visibility = if (BuildConfig.NLIC_LIBBOX_INCLUDED) View.VISIBLE else View.GONE
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SubscriptionLabActivity::class.java))
            }
        }

        binding.settingsButton.setOnClickListener {
            if (TestService.isRunning) {
                Toast.makeText(this, R.string.test_unavailable, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (appStatus.first == AppStatus.Running) {
                Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        binding.editorButton.setOnClickListener {
            if (BuildConfig.NLIC_LIBBOX_INCLUDED) {
                startActivity(Intent(this, SubscriptionLabActivity::class.java))
                return@setOnClickListener
            }
            val useCmdSettings = getPreferences().getCmdEnable()

            if (!useCmdSettings && appStatus.first == AppStatus.Running) {
                Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(this, SettingsActivity::class.java)
            intent.putExtra("open_fragment", if (useCmdSettings) "cmd" else "ui")
            startActivity(intent)
        }

        binding.testProxyButton.setOnClickListener {
            startActivity(Intent(this, TestActivity::class.java))
        }

        binding.domainListsButton.setOnClickListener {
            val intent = Intent(this, TestSettingsActivity::class.java)
            intent.putExtra("open_fragment", "domain_lists")
            startActivity(intent)
        }

        binding.strategyButton.setOnClickListener {
            if (BuildConfig.NLIC_LIBBOX_INCLUDED) {
                startActivity(Intent(this, TestActivity::class.java))
            } else showStrategyPicker()
        }

        if (!PermissionUtils.hasNotificationPermission(this)) {
            PermissionUtils.requestNotificationPermission(this, 1)
        }
        // Battery optimization exemption is optional and only requested from Settings.

        if (!TestService.isRunning && getPreferences().getBoolean("auto_connect", false) &&
            appStatus.first != AppStatus.Running) {
            this.start()
        }

        ShortcutUtils.update(this)
        if (savedInstanceState == null) handleShortcutIntent(intent)
    }

    /**
     * Shortcuts can be sent by any launcher/app because MainActivity is public.
     * Never execute an external network-control request without visible consent.
     * A strategy must also be an existing pinned entry, not arbitrary arguments.
     */
    private fun handleShortcutIntent(source: Intent?) {
        val action = source?.getStringExtra("nlic_shortcut_action") ?: return
        if (action != "toggle" && action != "strategy") return
        val strategy = source.getStringExtra("nlic_shortcut_strategy")
        if (action == "strategy") {
            if (strategy.isNullOrBlank() || strategy.length > 4096 ||
                HistoryUtils(this).getHistory().none { it.pinned && it.text == strategy }
            ) {
                Log.w(TAG, "Ignoring unrecognized strategy shortcut")
                return
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.nlic_shortcut_confirm_title)
            .setMessage(R.string.nlic_shortcut_confirm_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (action == "strategy" && !strategy.isNullOrBlank()) {
                    getPreferences().edit(commit = true) {
                        putString("byedpi_cmd_args", strategy)
                    }
                    updateStrategyButton()
                    if (appStatus.first == AppStatus.Running) {
                        ServiceManager.restart(this, getPreferences().mode())
                    } else {
                        start()
                    }
                } else if (appStatus.first == AppStatus.Running) {
                    stop()
                } else {
                    start()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        ServiceManager.refresh(this)
        updateStatus()
        updateStrategyButton()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_diagnostics -> {
                showDiagnostics()
                true
            }

            R.id.action_save_logs -> {
                val intent = Intent(this, FileActivity::class.java)
                intent.putExtra(FileActivity.EXTRA_MODE, FileActivity.MODE_CREATE)
                intent.putExtra(FileActivity.EXTRA_TYPE, FileActivity.TYPE_LOGS)
                logsRegister.launch(intent)
                true
            }

            R.id.action_close_app -> {
                TestService.stop()
                lifecycleScope.launch {
                    try {
                        TestService.awaitStopped()
                        ServiceManager.waitStop()
                    } catch (exception: Exception) {
                        Log.e(TAG, "Failed to stop service before closing", exception)
                        return@launch
                    }
                    finishAffinity()
                    android.os.Process.killProcess(android.os.Process.myPid())
                    exitProcess(0)
                }
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showDiagnostics() {
        val report = DiagnosticUtils.buildReport(this)
        val padding = (24 * resources.displayMetrics.density).toInt()

        val textView = TextView(this).apply {
            text = report
            setPadding(padding, padding / 2, padding, padding / 2)
        }

        val scrollView = ScrollView(this).apply {
            addView(textView)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.diagnostics)
            .setView(scrollView)
            .setPositiveButton(R.string.diagnostic_copy) { _, _ ->
                ClipboardUtils.copy(this, report, getString(R.string.diagnostics))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val copyButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            copyButton.nextFocusLeftId = cancelButton.id
            copyButton.nextFocusRightId = cancelButton.id
            cancelButton.nextFocusLeftId = copyButton.id
            cancelButton.nextFocusRightId = copyButton.id
            copyButton.requestFocus()
        }
        dialog.show()
    }

    private fun start() {
        if (TestService.isRunning) {
            Toast.makeText(this, R.string.test_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        when (getPreferences().mode()) {
            Mode.VPN -> {
                val intentPrepare = VpnService.prepare(this)
                if (intentPrepare != null) {
                    vpnRegister.launch(intentPrepare)
                } else {
                    ServiceManager.start(this, Mode.VPN)
                }
            }

            Mode.Proxy -> ServiceManager.start(this, Mode.Proxy)
        }
    }

    private fun stop() {
        ServiceManager.stop()
    }

    private fun updateStatus() {
        val (status, mode) = appStatus

        Log.i(TAG, "Updating status: $status, $mode")

        val preferences = getPreferences()
        val (ip, port) = preferences.getProxyIpAndPort()

        binding.proxyAddress.text =
            if (BuildConfig.NLIC_LIBBOX_INCLUDED) {
                runCatching {
                    val catalog = SubscriptionVault.read(applicationContext)
                    val selected = catalog.allNodes.firstOrNull {
                        it.id == catalog.selectedNodeId
                    }
                    selected?.let { it.name + " · " + it.protocol }
                        ?: getString(R.string.nlic_control_start_hint)
                }.getOrDefault(getString(R.string.nlic_control_status_hint))
            } else getString(R.string.proxy_address, ip, port)

        when (status) {
            AppStatus.Halted -> {
                binding.statusButtonCard.setCardBackgroundColor(
                    ContextCompat.getColor(this, R.color.nlic_surface_raised))
                binding.statusButtonIcon.setColorFilter(
                    ContextCompat.getColor(this, R.color.nlic_accent))

                when (preferences.mode()) {
                    Mode.VPN -> {
                        binding.statusText.setText(R.string.vpn_disconnected)
                    }

                    Mode.Proxy -> {
                        binding.statusText.setText(R.string.proxy_down)
                    }
                }
            }

            AppStatus.Running -> {
                binding.statusButtonCard.setCardBackgroundColor(
                    ContextCompat.getColor(this, R.color.nlic_success))
                binding.statusButtonIcon.setColorFilter(
                    ContextCompat.getColor(this, R.color.nlic_canvas))

                when (mode) {
                    Mode.VPN -> {
                        binding.statusText.setText(R.string.vpn_connected)
                    }

                    Mode.Proxy -> {
                        binding.statusText.setText(R.string.proxy_up)
                    }
                }
            }
        }
    }

    private fun updateStrategyButton() {
        if (BuildConfig.NLIC_LIBBOX_INCLUDED) {
            // No raw ByeDPI command-line text on the new VPN-first home.
            binding.cmdButtonsRow.visibility = View.VISIBLE
            binding.strategyButton.visibility = View.VISIBLE
            binding.strategyButtonName.visibility = View.VISIBLE
            binding.strategyButtonName.text =
                when (NetworkEnvironment.capture(this).type) {
                    AccessType.WIFI -> "Wi-Fi"
                    AccessType.CELLULAR -> getString(R.string.nlic_home_auto_network)
                    AccessType.ETHERNET -> "Ethernet"
                    else -> getString(R.string.nlic_home_connection)
                }
            binding.strategyButtonText.text =
                getString(R.string.nlic_home_policy_description)
            return
        }
        val useCmdSettings = getPreferences().getCmdEnable()

        if (!useCmdSettings) {
            binding.cmdButtonsRow.visibility = View.GONE
            binding.strategyButton.visibility = View.GONE
            return
        }

        binding.cmdButtonsRow.visibility = View.VISIBLE

        val pinned = historyUtils.getPinnedHistory()
        val currentCmdArgs = getPreferences().getCmdArgs()

        val matched = pinned.find { it.text == currentCmdArgs }
        val name = matched?.name?.takeIf { it.isNotBlank() }

        if (name != null) {
            binding.strategyButtonName.text = name
            binding.strategyButtonName.visibility = View.VISIBLE
            binding.strategyButtonText.maxLines = 1
        } else {
            binding.strategyButtonName.visibility = View.GONE
            binding.strategyButtonText.maxLines = 2
        }

        binding.strategyButtonText.text = currentCmdArgs.ifBlank { getString(R.string.main_strategy_picker) }
        binding.strategyButton.visibility = View.VISIBLE
    }

    private fun showStrategyPicker() {
        val pinned = historyUtils.getPinnedHistory()

        if (pinned.isEmpty()) {
            Toast.makeText(this, R.string.main_strategy_no_pinned, Toast.LENGTH_SHORT).show()
            return
        }

        val adapter = object : ArrayAdapter<Command>(
            this,
            R.layout.item_main_strategy,
            pinned
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(
                    R.layout.item_main_strategy,
                    parent,
                    false
                )

                val command = getItem(position)!!

                val nameView = view.findViewById<TextView>(R.id.strategyName)
                val textView = view.findViewById<TextView>(R.id.strategyText)
                val dividerView = view.findViewById<View>(R.id.strategyDivider)

                val name = command.name?.takeIf { it.isNotBlank() }

                if (name != null) {
                    nameView.visibility = View.VISIBLE
                    nameView.text = name
                    textView.maxLines = 2
                } else {
                    nameView.visibility = View.GONE
                    textView.maxLines = 3
                }

                textView.text = command.text
                dividerView.visibility = if (position == count - 1) View.GONE else View.VISIBLE

                return view
            }
        }

        val listView = ListView(this).apply {
            divider = null
            setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0)
            this.adapter = adapter
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.main_strategy_picker))
            .setView(listView)
            .setNegativeButton(getString(android.R.string.cancel), null)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            applyStrategy(pinned[position].text)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun applyStrategy(commandText: String) {
        getPreferences().edit { putString("byedpi_cmd_args", commandText) }

        updateStrategyButton()

        if (appStatus.first == AppStatus.Running) {
            val mode = getPreferences().mode()
            if (mode == Mode.VPN && VpnService.prepare(this) != null) {
                return
            }

            ServiceManager.restart(this, mode)
            Toast.makeText(this, R.string.service_restart, Toast.LENGTH_SHORT).show()
        }
    }

}
