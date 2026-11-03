package io.github.romanvht.byedpi.subscription

import android.content.Context
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.button.MaterialButton
import io.github.romanvht.byedpi.R

/**
 * Single connection dashboard. The public UI never displays subscription
 * credentials, endpoints, or a fabricated "connected" status.
 */
internal class NlicDashboardPreference(context: Context) : Preference(context) {
    var heading: CharSequence = ""
    var details: CharSequence = ""
    var hint: CharSequence = ""
    var connecting: Boolean = false
    var connectAllowed: Boolean = false
    var showDisconnect: Boolean = false
    var onConnect: (() -> Unit)? = null
    var onDisconnect: (() -> Unit)? = null
    var onHelp: (() -> Unit)? = null

    init {
        layoutResource = R.layout.preference_nlic_dashboard
        isSelectable = false
        isPersistent = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.nlic_dashboard_title) as TextView).text = heading
        (holder.findViewById(R.id.nlic_dashboard_details) as TextView).text = details
        (holder.findViewById(R.id.nlic_dashboard_hint) as TextView).text = hint

        val progress = holder.findViewById(R.id.nlic_dashboard_progress) as ProgressBar
        progress.visibility = if (connecting) View.VISIBLE else View.GONE

        val connect = holder.findViewById(R.id.nlic_dashboard_connect) as MaterialButton
        connect.text = context.getString(
            if (connecting) R.string.nlic_core_connecting else R.string.nlic_core_connect
        )
        connect.isEnabled = !connecting && connectAllowed
        connect.setOnClickListener { onConnect?.invoke() }

        val disconnect = holder.findViewById(R.id.nlic_dashboard_disconnect) as MaterialButton
        disconnect.text = context.getString(R.string.nlic_core_disconnect)
        disconnect.visibility = if (showDisconnect) View.VISIBLE else View.GONE
        disconnect.isEnabled = !connecting
        disconnect.setOnClickListener { onDisconnect?.invoke() }

        val help = holder.findViewById(R.id.nlic_dashboard_help) as TextView
        help.text = context.getString(R.string.nlic_ui_help)
        help.setOnClickListener { onHelp?.invoke() }
    }
}
