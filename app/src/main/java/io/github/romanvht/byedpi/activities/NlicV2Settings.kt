package io.github.romanvht.byedpi.activities

import android.content.Intent
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.utility.getPreferences

internal object NlicV2Settings {
    fun show(activity: AppCompatActivity, running: Boolean, clear: () -> Unit) {
        val dialog = BottomSheetDialog(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(32))
        }
        fun row(title: String, click: () -> Unit) {
            root.addView(TextView(activity).apply {
                text = title
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), 0, dp(8))
                minHeight = dp(55)
                setOnClickListener { dialog.dismiss(); click() }
            }, LinearLayout.LayoutParams(-1, dp(55)))
        }
        root.addView(TextView(activity).apply {
            text = "Настройки"
            textSize = 22f
            setPadding(dp(8), 0, 0, dp(12))
        })
        row("Ядро локального обхода") {
            if (running) {
                AlertDialog.Builder(activity)
                    .setMessage("Для смены ядра сначала отключи соединение.")
                    .setPositiveButton(android.R.string.ok, null).show()
            } else {
                val prefs = activity.getPreferences()
                val experimental = prefs.getBoolean("nlic_v2_native_opt_in", false)
                val options = arrayOf("Стабильное ByeDPI", "Экспериментальное N-LIC V2")
                AlertDialog.Builder(activity)
                    .setTitle("Ядро локального обхода")
                    .setSingleChoiceItems(options, if (experimental) 1 else 0) { whichDialog, selected ->
                        if (selected == 0) {
                            prefs.edit().putBoolean("nlic_v2_native_opt_in", false).commit()
                            whichDialog.dismiss()
                        } else {
                            whichDialog.dismiss()
                            AlertDialog.Builder(activity)
                                .setTitle("Экспериментальное ядро")
                                .setMessage("Проверка только для режима «Обход DPI». Движок не заменяет Xray, не шифрует IP-трафик и ещё не прошёл испытания на утечки. Если интернет перестанет работать, верни стабильное ByeDPI.")
                                .setPositiveButton("Включить") { _, _ ->
                                    prefs.edit().putBoolean("nlic_v2_native_opt_in", true).commit()
                                }
                                .setNegativeButton(android.R.string.cancel, null).show()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }
        row("Защита VPN Android") {
            val intent = Intent("android.settings.VPN_SETTINGS")
            if (intent.resolveActivity(activity.packageManager) != null)
                activity.startActivity(intent)
        }
        row("Удалить сохранённые серверы") {
            AlertDialog.Builder(activity)
                .setTitle(R.string.nlic_subscriptions_clear)
                .setMessage(R.string.nlic_subscriptions_clear_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ -> clear() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        dialog.setContentView(root)
        dialog.show()
    }
}
