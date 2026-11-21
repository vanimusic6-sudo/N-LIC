package io.github.romanvht.byedpi.activities

import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import io.github.romanvht.byedpi.subscription.SubscriptionCatalog
import io.github.romanvht.byedpi.subscription.SubscriptionFetcher
import io.github.romanvht.byedpi.subscription.SubscriptionGroup
import io.github.romanvht.byedpi.subscription.SubscriptionImport
import io.github.romanvht.byedpi.subscription.SubscriptionVault
import io.github.romanvht.byedpi.subscription.TvPairTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Phone-only, user-confirmed encrypted subscription sender. */
class TvSendActivity : BaseActivity() {
    private var target: TvPairTransfer.Target? = null
    private lateinit var ip: EditText
    private lateinit var port: EditText
    private lateinit var code: EditText
    private lateinit var send: MaterialButton
    private lateinit var status: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        target = TvPairTransfer.readTarget(intent?.data)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(24)
            setPadding(p, dp(28), p, dp(32))
        }
        val scroller = ScrollView(this).apply { addView(root) }
        setContentView(scroller)

        root.addView(label("N-LIC · на телевизор", 28))
        root.addView(label(
            "Телефон и телевизор должны быть в одной сети Wi-Fi. " +
                "На ТВ откройте «Получить с телефона».", 17
        ))
        val qr = target
        if (qr != null) {
            root.addView(label("QR-код считан. Телевизор: " +
                qr.ip + ":" + qr.port, 19))
        } else {
            root.addView(label("Введите адрес и код, показанные на телевизоре:", 18))
        }

        ip = field("IP телевизора (например, 192.168.1.5)",
            qr?.ip.orEmpty(), InputType.TYPE_CLASS_TEXT)
        port = field("Порт с экрана ТВ", qr?.port?.toString().orEmpty(),
            InputType.TYPE_CLASS_NUMBER)
        code = field("Код сопряжения с экрана ТВ",
            qr?.let { TvPairTransfer.encodeKey(it.secret) }.orEmpty(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        val manual = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ip)
            addView(port)
            addView(code)
            visibility = if (qr == null) View.VISIBLE else View.GONE
        }
        root.addView(manual)

        send = MaterialButton(this).apply {
            text = "Выбрать и отправить подписку"
            setAllCaps(false)
            textSize = 17f
            minHeight = dp(64)
            setOnClickListener { chooseSubscription() }
        }
        root.addView(send)
        status = label(
            "Подписка будет зашифрована и передана напрямую на телевизор. " +
                "Адреса серверов и ключи не отправляются на сторонние сайты.", 15
        )
        root.addView(status)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun label(value: String, size: Int): TextView = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setPadding(0, 0, 0, dp(18))
    }

    private fun field(hintText: String, value: String, type: Int): EditText =
        EditText(this).apply {
            hint = hintText
            inputType = type
            setText(value)
            isSingleLine = true
            maxLines = 1
            filters = arrayOf(InputFilter.LengthFilter(4096))
        }

    private fun selectedTarget(): TvPairTransfer.Target? {
        target?.let { return it }
        val query = Uri.Builder().scheme("nlic").authority("pair")
            .appendQueryParameter("ip", ip.text.toString().trim())
            .appendQueryParameter("port", port.text.toString().trim())
            .appendQueryParameter("key", code.text.toString().filterNot(Char::isWhitespace))
            .build()
        return TvPairTransfer.readTarget(query)
    }

    private fun chooseSubscription() {
        if (busy) return
        if (selectedTarget() == null) {
            status.text = "Неверный адрес или код. Проверьте экран телевизора."
            return
        }
        lifecycleScope.launch {
            val catalog = try {
                withContext(Dispatchers.IO) { SubscriptionVault.read(applicationContext) }
            } catch (_: Exception) {
                SubscriptionCatalog()
            }
            val choices = mutableListOf<String>()
            val groups = catalog.groups
            groups.forEach { choices.add(it.title.take(80)) }
            if (catalog.manual.isNotEmpty()) choices.add("Все отдельные серверы N-LIC")
            choices.add("Вставить ссылку или конфигурацию")
            AlertDialog.Builder(this@TvSendActivity)
                .setTitle("Что отправить на телевизор?")
                .setItems(choices.toTypedArray()) { _, selected ->
                    when {
                        selected < groups.size -> sendPayload {
                            TvPairTransfer.Payload(groups = listOf(groups[selected]))
                        }
                        selected == groups.size && catalog.manual.isNotEmpty() ->
                            sendPayload { TvPairTransfer.Payload(manual = catalog.manual) }
                        else -> pasteSubscription()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    private fun pasteSubscription() {
        val field = EditText(this).apply {
            hint = "https://... или vless://... или другой профиль"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 5
            filters = arrayOf(InputFilter.LengthFilter(512 * 1024))
        }
        val wrapper = LinearLayout(this).apply {
            val pad = dp(20)
            setPadding(pad, 0, pad, 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle("Вставьте подписку на телефоне")
            .setView(wrapper)
            .setPositiveButton("Отправить") { _, _ ->
                val text = field.text.toString().trim()
                if (text.isNotBlank()) sendPayload {
                    if (text.startsWith("https://", ignoreCase = true)) {
                        require(SubscriptionFetcher.validSubscriptionUrl(text))
                        val nodes = SubscriptionImport.parse(
                            SubscriptionFetcher.fetch(text), "TV subscription"
                        )
                        require(nodes.isNotEmpty())
                        TvPairTransfer.Payload(groups = listOf(
                            SubscriptionGroup("С телефона", text, nodes,
                                System.currentTimeMillis())
                        ))
                    } else {
                        val nodes = SubscriptionImport.parse(text, "TV")
                        require(nodes.isNotEmpty())
                        TvPairTransfer.Payload(manual = nodes)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sendPayload(makePayload: suspend () -> TvPairTransfer.Payload) {
        val destination = selectedTarget() ?: return
        busy = true
        send.isEnabled = false
        status.text = "Передаём подписку по локальной сети…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val payload = makePayload()
                    TvPairTransfer.send(applicationContext, destination, payload)
                }.getOrDefault(false)
            }
            status.text = if (result) {
                "Готово! Телевизор получил и сохранил подписку."
            } else {
                "Не удалось передать. Проверьте одну Wi-Fi сеть, " +
                    "срок действия QR-кода и поддержку профилей."
            }
            if (result) {
                Toast.makeText(this@TvSendActivity, "Подписка на ТВ!", Toast.LENGTH_LONG).show()
            }
            busy = false
            send.isEnabled = true
        }
    }

    override fun onDestroy() {
        target?.secret?.fill(0)
        super.onDestroy()
    }
}
