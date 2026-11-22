package io.github.romanvht.byedpi.activities

import android.content.Intent
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import io.github.romanvht.byedpi.BuildConfig
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.subscription.RealityAssistTrace
import io.github.romanvht.byedpi.subscription.RealityAssistMemory
import io.github.romanvht.byedpi.subscription.RealityTcpTricks
import io.github.romanvht.byedpi.subscription.PacketDesyncPolicy
import io.github.romanvht.byedpi.subscription.SubscriptionVault
import io.github.romanvht.byedpi.utility.getPreferences

/**
 * The main screen deliberately exposes only Mode, Server, Auto, Bay, Power.
 * All the other existing features remain reachable from this compact sheet,
 * instead of disappearing behind invisible legacy controls.
 */
internal object NlicV2Settings {
    fun show(
        activity: AppCompatActivity,
        running: Boolean,
        onRealityModeChanged: () -> Unit = {},
        onRealityWorkbench: () -> Unit = {},
        clear: () -> Unit,
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(n: Int) = (n * density + .5f).toInt()
        val normal = ContextCompat.getColor(activity, R.color.nlic_text)
        val subdued = ContextCompat.getColor(activity, R.color.nlic_muted)
        val surface = ContextCompat.getColor(activity, R.color.nlic_canvas)
        val dialog = BottomSheetDialog(activity)
        val outer = ScrollView(activity).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            setBackgroundColor(surface)
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(30))
        }
        outer.addView(root)

        fun section(name: String) {
            root.addView(TextView(activity).apply {
                text = name
                setTextColor(subdued)
                textSize = 12f
                letterSpacing = 0.03f
                setPadding(dp(4), dp(17), 0, dp(6))
            })
        }
        fun action(title: String, detail: String? = null, perform: () -> Unit) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(if (detail == null) 52 else 67)
                setPadding(dp(8), dp(7), dp(8), dp(7))
                background = android.util.TypedValue().run {
                    activity.theme.resolveAttribute(
                        android.R.attr.selectableItemBackground, this, true)
                    runCatching { activity.getDrawable(resourceId) }.getOrNull()
                }
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    dialog.dismiss()
                    perform()
                }
            }
            row.addView(TextView(activity).apply {
                text = title
                setTextColor(normal)
                textSize = 15f
            })
            if (detail != null) row.addView(TextView(activity).apply {
                text = detail
                setTextColor(subdued)
                textSize = 12f
                setPadding(0, dp(3), 0, 0)
            })
            root.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        fun requireDisconnected(action: () -> Unit) {
            if (!running) action()
            else AlertDialog.Builder(activity)
                .setMessage("Для изменения настроек соединения сначала отключи VPN.")
                .setPositiveButton(android.R.string.ok, null).show()
        }
        root.addView(TextView(activity).apply {
            text = "Настройки"
            setTextColor(normal)
            textSize = 22f
            setPadding(dp(4), dp(2), 0, dp(12))
        })

        section("Приложение")
        action("Все настройки", "Оформление, язык, автоматизация, сеть и параметры приложения") {
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
        }
        section("Обход DPI")
        if (BuildConfig.NLIC_XRAY_INCLUDED) {
            action(
                "REALITY · профиль и проверка",
                "LIC-helper, ручной транспорт и проверка вариантов"
            ) {
                requireDisconnected { onRealityWorkbench() }
            }
        }
        if (BuildConfig.NLIC_XRAY_INCLUDED) {
            action("LIC-Bay · пакеты TCP",
                "Fake TTL или OOB вместо разрезания TLS. Только для REALITY/RAW, без root.") {
                requireDisconnected {
                    val prefs = activity.getPreferences()
                    val activeMode = if (!prefs.getBoolean(PacketDesyncPolicy.PREF_KEY, true))
                        PacketDesyncPolicy.LEGACY
                    else prefs.getString(PacketDesyncPolicy.MODE_KEY,
                        PacketDesyncPolicy.FAKE_TTL)
                    val modes = listOf(PacketDesyncPolicy.FAKE_TTL,
                        PacketDesyncPolicy.DISOOB, PacketDesyncPolicy.LEGACY)
                    AlertDialog.Builder(activity)
                        .setTitle("Пакетная помощь LIC-Bay")
                        .setSingleChoiceItems(arrayOf(
                            "Fake TTL · ложная TCP-запись с ограниченным TTL",
                            "TCP OOB · внеполосный байт в начале TLS",
                            "Отключить · оставить прежний профиль ByeDPI"
                        ), modes.indexOf(activeMode).coerceAtLeast(0)) { d, position ->
                            val mode = modes.getOrNull(position)
                                ?: PacketDesyncPolicy.LEGACY
                            prefs.edit()
                                .putBoolean(PacketDesyncPolicy.PREF_KEY,
                                    mode != PacketDesyncPolicy.LEGACY)
                                .putString(PacketDesyncPolicy.MODE_KEY, mode)
                                .apply()
                            d.dismiss()
                        }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
            }
        }
        action("Ядро локального обхода",
            "Стабильный ByeDPI или экспериментальное N-LIC V2") {
            requireDisconnected {
                val prefs = activity.getPreferences()
                val experimental = prefs.getBoolean("nlic_v2_native_opt_in", false)
                AlertDialog.Builder(activity)
                    .setTitle("Ядро локального обхода")
                    .setSingleChoiceItems(
                        arrayOf("ByeDPI · стабильное", "N-LIC V2 · экспериментальное"),
                        if (experimental) 1 else 0
                    ) { popup, selected ->
                        popup.dismiss()
                        if (selected == 0) {
                            prefs.edit().putBoolean("nlic_v2_native_opt_in", false).commit()
                        } else {
                            AlertDialog.Builder(activity)
                                .setTitle("Экспериментальное ядро")
                                .setMessage("Только для локального обхода. N-LIC V2 не заменяет Xray, не скрывает IP-адрес и ещё не прошло полный аудит утечек.")
                                .setPositiveButton("Включить") { _, _ ->
                                    prefs.edit().putBoolean("nlic_v2_native_opt_in", true).commit()
                                }
                                .setNegativeButton(android.R.string.cancel, null).show()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }
        action("Параметры ByeDPI", "Визуальный редактор локального обхода") {
            requireDisconnected {
                activity.startActivity(Intent(activity, SettingsActivity::class.java)
                    .putExtra("open_fragment", "ui"))
            }
        }
        action("Проверить стратегии", "Подобрать обход по результатам реальных тестов") {
            requireDisconnected { activity.startActivity(Intent(activity, TestActivity::class.java)) }
        }
        action("Домены и исключения", "Списки адресов для локального обхода") {
            requireDisconnected {
                activity.startActivity(Intent(activity, TestSettingsActivity::class.java)
                    .putExtra("open_fragment", "domain_lists"))
            }
        }

        // LIC-Assist controls and all strategy tests live together on the
        // VPN home screen. Duplicate advanced menus were confusing and could
        // disagree with the currently active main-screen switch.

        section("Сеть")
        action("DNS, IPv6 и приложения", "Маршрутизация, исключения и расширенные параметры") {
            requireDisconnected {
                activity.startActivity(Intent(activity, SettingsActivity::class.java))
            }
        }
        action("Диагностика сети", "Проверка доступности, DNS и подключения") {
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
        }
        action("Защита Android VPN", "Всегда включённый VPN и блокировка соединений без VPN") {
            val intent = Intent("android.settings.VPN_SETTINGS")
            if (intent.resolveActivity(activity.packageManager) != null) {
                activity.startActivity(intent)
            } else AlertDialog.Builder(activity)
                .setMessage("Настройки VPN недоступны на этом устройстве.")
                .setPositiveButton(android.R.string.ok, null).show()
        }

        section("Диагностика и данные")
        action("Состояние соединения", "Без серверов, токенов и содержимого трафика") {
            val prefs = activity.getPreferences()
            val mode = prefs.getString("nlic_connection_mode", "subscription")
            val engine = when {
                mode != "local" -> "Xray-core"
                prefs.getBoolean("nlic_v2_native_opt_in", false) -> "N-LIC V2"
                else -> "ByeDPI"
            }
            val failure = ServiceManager.lastCoreFailureCode
                ?.takeIf { it.matches(Regex("[A-Z0-9_]{1,64}")) } ?: "—"
            val uTlsAssist = when (prefs.getString("nlic_reality_tcp_utls", "profile")) {
                "smart" -> "Умный подбор из Chrome/Firefox/Safari · кэш 12 ч"
                "firefox" -> "Firefox (если совместимо)"
                "chrome" -> "Chrome (если совместимо)"
                "safari" -> "Safari (если совместимо)"
                else -> "по конфигурации"
            }
            val report = "N-LIC ${BuildConfig.VERSION_NAME}\nAndroid API ${Build.VERSION.SDK_INT}" +
                "\nРежим: ${if (mode == "local") "Обход DPI" else "VPN-сервер"}" +
                "\nЯдро: $engine\nVPN запущен: ${if (running) "да" else "нет"}" +
                "\nREALITY TCP uTLS: $uTlsAssist\nПоследний код ошибки: $failure"
            AlertDialog.Builder(activity).setTitle("Диагностика")
                .setMessage(report)
                .setPositiveButton(android.R.string.ok, null).show()
        }
        action("Очистить серверы", "Удаляет локальные подписки и сохранённые конфигурации") {
            AlertDialog.Builder(activity)
                .setTitle(R.string.nlic_subscriptions_clear)
                .setMessage(R.string.nlic_subscriptions_clear_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ -> clear() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        dialog.setContentView(outer)
        dialog.setOnShowListener {
            // The sheet must not stop at its default peek height: every settings
            // row remains reachable even with gesture navigation and font scaling.
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.let { sheet ->
                    sheet.layoutParams = sheet.layoutParams.apply {
                        height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    }
                    BottomSheetBehavior.from(sheet).apply {
                        isFitToContents = true
                        skipCollapsed = true
                        state = BottomSheetBehavior.STATE_EXPANDED
                    }
                }
        }
        dialog.show()
    }
}
