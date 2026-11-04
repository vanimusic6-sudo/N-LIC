package io.github.romanvht.byedpi.activities

import android.content.Intent
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.github.romanvht.byedpi.R

internal object NlicV2Settings {
    fun show(activity: AppCompatActivity, clear: () -> Unit) {
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
