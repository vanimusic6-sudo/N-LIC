package io.github.romanvht.byedpi.subscription

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.Gson
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * App-private AES-256-GCM encrypted subscription store. No plaintext config
 * or subscription URL in SharedPreferences, backup, logs or exported metadata.
 *
 * Import requires API 23+; earlier Android devices keep legacy ByeDPI only.
 */
// Every public entry point gates the API-23 Keystore at runtime. Android 5
// retains the old ByeDPI mode and never reaches these methods.
@android.annotation.SuppressLint("NewApi")
internal object SubscriptionVault {
    private const val FILE = "nlic_subscription_vault.v1"
    private const val KEY_ALIAS = "nlic_subscriptions_aes256_v1"
    private const val MAX_FILE_BYTES = 2 * 1024 * 1024
    private val gson = Gson()

    fun available(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    @Synchronized fun read(context: Context): SubscriptionCatalog {
        require(available()) { "Subscriptions require Android 6.0 or newer" }
        val file = File(context.filesDir, FILE)
        // AtomicFile may have a recoverable .bak after a process crash.
        // Never read an unbounded stream into heap before checking its size.
        if (!file.exists() && !File(file.absolutePath + ".bak").exists())
            return SubscriptionCatalog()
        val bytes = AtomicFile(file).openRead().use(::readBoundedCiphertext)
        try {
            val iv = bytes.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(bytes, 12, bytes.size - 12)
            try {
                val decoded = gson.fromJson(String(plain, Charsets.UTF_8), SubscriptionCatalog::class.java)
                    ?: return SubscriptionCatalog()
                require(decoded.groups.size <= 30 && decoded.allNodes.size <= 500) {
                    "Subscription vault exceeds limits"
                }
                return decoded
            } finally {
                plain.fill(0)
            }
        } catch (_: Exception) {
            // Do not include raw exception info: crypto exception messages
            // can accidentally include paths and provider-specific details.
            throw IllegalStateException("Cannot open the encrypted subscription store")
        } finally {
            bytes.fill(0)
        }
    }

    @Synchronized fun write(context: Context, data: SubscriptionCatalog) {
        require(available()) { "Subscriptions require Android 6.0 or newer" }
        require(data.groups.size <= 30 && data.allNodes.size <= 500) { "Too many subscriptions" }
        val plain = gson.toJson(data).toByteArray(Charsets.UTF_8)
        require(plain.size < MAX_FILE_BYTES - 32) { "Subscriptions are too large" }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.iv + cipher.doFinal(plain)
            val file = AtomicFile(File(context.filesDir, FILE))
            var out: java.io.FileOutputStream? = null
            try {
                out = file.startWrite()
                out.write(encrypted)
                file.finishWrite(out)
            } catch (e: Exception) {
                if (out != null) file.failWrite(out)
                throw IllegalStateException("Could not save subscriptions")
            } finally {
                encrypted.fill(0)
            }
        } finally {
            plain.fill(0)
        }
    }

    fun addManual(context: Context, nodes: List<SubscriptionNode>): Int {
        if (nodes.isEmpty()) return 0
        val old = read(context)
        val unique = (old.manual + nodes).distinctBy { it.id }.take(500)
        write(context, old.copy(manual = unique))
        return unique.size - old.manual.size
    }

    fun updateGroup(context: Context, group: SubscriptionGroup) {
        val old = read(context)
        val existing = old.groups.filterNot { it.url == group.url }
        write(context, old.copy(groups = existing + group))
    }

    @Synchronized fun clear(context: Context) {
        // AtomicFile.delete removes both main file and its backup. Deleting
        // only the main file could leave stale encrypted subscription URLs.
        AtomicFile(File(context.filesDir, FILE)).delete()
        if (available()) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
        }
    }

    fun select(context: Context, nodeId: String) {
        val data = read(context)
        require(data.allNodes.any { it.id == nodeId }) { "Unknown node" }
        write(context, data.copy(selectedNodeId = nodeId))
    }

    internal fun readBoundedCiphertext(input: InputStream): ByteArray {
        val buffer = ByteArray(8192)
        val output = ByteArrayOutputStream()
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_FILE_BYTES) {
                "Subscription vault is too large"
            }
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        require(bytes.size in 29..MAX_FILE_BYTES) {
            "Corrupt subscription vault"
        }
        return bytes
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }
}
