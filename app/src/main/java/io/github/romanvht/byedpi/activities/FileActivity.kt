package io.github.romanvht.byedpi.activities

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import io.github.romanvht.byedpi.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Opens the system Storage Access Framework. N-LIC never needs broad device
 * storage access for importing/exporting settings or saving diagnostics.
 *
 * The legacy FileActivity intent/result contract remains intact so callers
 * can keep using EXTRA_MODE, EXTRA_TYPE and URI results.
 */
class FileActivity : BaseActivity() {
    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_TYPE = "type"
        const val EXTRA_PATH = "path" // legacy result value; always omitted for SAF
        const val MODE_OPEN = "open"
        const val MODE_CREATE = "create"
        const val TYPE_SETTINGS = "settings"
        const val TYPE_LOGS = "logs"
        private const val TAG = "NlicFilePicker"
    }

    private val mode: String get() = intent.getStringExtra(EXTRA_MODE) ?: MODE_OPEN
    private val type: String get() = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_SETTINGS

    private val picker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                val response = Intent().apply {
                    data = uri
                    putExtra(EXTRA_MODE, mode)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                setResult(Activity.RESULT_OK, response)
            }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return

        val create = mode == MODE_CREATE
        val logs = type == TYPE_LOGS
        val extension = if (logs) "log" else "json"
        val filePrefix = if (logs) "nlic" else "nlic_settings"
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date())
        val chooseFile = Intent(
            if (create) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT
        ).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (logs) "text/plain" else "application/json"
            if (create) putExtra(Intent.EXTRA_TITLE, "${filePrefix}_${stamp}.${extension}")
        }
        try {
            picker.launch(chooseFile)
        } catch (error: ActivityNotFoundException) {
            Log.e(TAG, "No document provider is available", error)
            Toast.makeText(this, R.string.storage_access_not_granted, Toast.LENGTH_SHORT).show()
            finish()
        } catch (error: SecurityException) {
            Log.e(TAG, "Document provider access denied", error)
            Toast.makeText(this, R.string.storage_access_not_granted, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
