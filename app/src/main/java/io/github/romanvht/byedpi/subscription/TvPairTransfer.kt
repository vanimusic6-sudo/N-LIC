package io.github.romanvht.byedpi.subscription

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Base64
import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Ephemeral, explicit, same-LAN transfer. The QR carries a random 256-bit
 * secret; only AES-GCM encrypted bytes are sent over TCP. No web relay, open
 * HTTP endpoint, persistent pairing credential, or clipboard export.
 *
 * QR possession is authentication: don't photograph/share pairing codes.
 * A pairing listener is active only while the TV dialog is open (120 s max).
 */
internal object TvPairTransfer {
    private const val MAGIC = 0x4e545631 // NTV1
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val TTL_MS = 120_000L
    private val aad = "N-LIC-TV-PAIR-v1".toByteArray(StandardCharsets.UTF_8)
    private val gson = Gson()

    data class Target(val ip: String, val port: Int, val secret: ByteArray)

    @Keep
    data class Payload(
        @SerializedName("groups") val groups: List<SubscriptionGroup> = emptyList(),
        @SerializedName("manual") val manual: List<SubscriptionNode> = emptyList(),
    )

    internal fun privateIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 ||
                it.any { c -> c !in '0'..'9' } }) return false
        val bytes = parts.map { it.toIntOrNull() ?: return false }
        if (bytes.any { it !in 0..255 }) return false
        return bytes[0] == 10 ||
            (bytes[0] == 172 && bytes[1] in 16..31) ||
            (bytes[0] == 192 && bytes[1] == 168)
    }

    internal fun encodeKey(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun targetUri(ip: String, port: Int, key: ByteArray): Uri =
        Uri.Builder().scheme("nlic").authority("pair")
            .appendQueryParameter("ip", ip)
            .appendQueryParameter("port", port.toString())
            .appendQueryParameter("key", encodeKey(key)).build()

    fun readTarget(uri: Uri?): Target? {
        if (uri?.scheme != "nlic" || uri.host != "pair") return null
        val ip = uri.getQueryParameter("ip") ?: return null
        val port = uri.getQueryParameter("port")?.toIntOrNull() ?: return null
        val encodedKey = uri.getQueryParameter("key") ?: return null
        if (!privateIpv4(ip) || port !in 1024..65535 ||
            encodedKey.length !in 42..44) return null
        val key = runCatching {
            Base64.decode(encodedKey, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull() ?: return null
        if (key.size != 32) return null
        return Target(ip, port, key)
    }

    private fun lanIp(): String? {
        val cards = NetworkInterface.getNetworkInterfaces() ?: return null
        return Collections.list(cards)
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }
                .getOrDefault(false) && !it.name.startsWith("tun") }
            .sortedBy { card ->
                when {
                    card.name.startsWith("wlan") -> 0
                    card.name.startsWith("eth") -> 1
                    else -> 2
                }
            }.asSequence()
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress.orEmpty() }
            .firstOrNull(::privateIpv4)
    }

    fun openServer(context: Context, onResult: (Boolean, Int) -> Unit): PairServer {
        val ip = lanIp() ?: error("Local Wi-Fi/Ethernet IPv4 is unavailable")
        val address = InetAddress.getByName(ip) // validated IPv4 literal, no DNS
        val server = ServerSocket(0, 3, address)
        val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return PairServer(context.applicationContext, ip, server, secret, onResult)
    }

    class PairServer internal constructor(
        private val context: Context,
        val ip: String,
        private val server: ServerSocket,
        private val secret: ByteArray,
        private val onResult: (Boolean, Int) -> Unit,
    ) : AutoCloseable {
        val port: Int get() = server.localPort
        val pairingUri: Uri get() = targetUri(ip, port, secret)
        val pairingCode: String get() = encodeKey(secret)
        private val closed = AtomicBoolean(false)

        fun start() {
            thread(name = "nlic-tv-pair", isDaemon = true) {
                var succeeded = false
                var count = 0
                val deadline = System.nanoTime() + TTL_MS * 1_000_000L
                try {
                    var attempts = 0
                    while (!closed.get() && attempts < 3 &&
                        System.nanoTime() < deadline) {
                        server.soTimeout = minOf(10_000L,
                            (deadline - System.nanoTime()) / 1_000_000L
                        ).coerceAtLeast(1L).toInt()
                        val client = try { server.accept() }
                        catch (_: java.net.SocketTimeoutException) { continue }
                        attempts++
                        val imported = runCatching { receive(client) }.getOrNull() ?: 0
                        if (imported > 0) {
                            succeeded = true
                            count = imported
                            break
                        }
                    }
                } catch (_: Exception) {
                    // Never log pairing URLs, secrets, subscription data or IPs.
                } finally {
                    val notify = !closed.get()
                    close()
                    if (notify) onResult(succeeded, count)
                }
            }
        }

        private fun receive(client: Socket): Int = client.use { socket ->
            socket.soTimeout = 12_000
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            if (input.readInt() != MAGIC) return@use 0
            val iv = ByteArray(12).also(input::readFully)
            val size = input.readInt()
            if (size !in 17..MAX_BYTES) return@use 0
            val ciphertext = ByteArray(size).also(input::readFully)
            val plain = decrypt(secret, iv, ciphertext)
            val payload = try {
                gson.fromJson(String(plain, StandardCharsets.UTF_8), Payload::class.java)
                    ?: return@use 0
            } finally {
                plain.fill(0)
            }
            // Store only after authenticated decryption and strict parsing.
            val total = merge(context, payload)
            if (total <= 0) return@use 0
            val ack = acknowledgement(secret, ciphertext)
            output.writeByte(1)
            output.write(ack)
            output.flush()
            total
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                runCatching { server.close() }
                secret.fill(0)
            }
        }
    }

    /** This method is only called after an explicit phone UI confirmation. */
    fun send(context: Context, target: Target, payload: Payload): Boolean {
        require(privateIpv4(target.ip) && target.port in 1024..65535)
        require(target.secret.size == 32)
        val plain = gson.toJson(payload).toByteArray(StandardCharsets.UTF_8)
        try {
            require(plain.size in 2 until MAX_BYTES - 16)
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val ciphertext = encrypt(target.secret, iv, plain)
            // If a VPN is active on the phone, use the underlying LAN
            // instead of inadvertently tunnelling the local TV address.
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager
            val lan = manager.allNetworks.firstOrNull { network ->
                val caps = manager.getNetworkCapabilities(network)
                caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            }
            val socket = lan?.socketFactory?.createSocket() ?: Socket()
            return socket.use {
                it.connect(InetSocketAddress(target.ip, target.port), 8_000)
                it.soTimeout = 15_000
                val output = DataOutputStream(BufferedOutputStream(it.getOutputStream()))
                output.writeInt(MAGIC)
                output.write(iv)
                output.writeInt(ciphertext.size)
                output.write(ciphertext)
                output.flush()
                val input = DataInputStream(BufferedInputStream(it.getInputStream()))
                val status = input.readUnsignedByte()
                val receivedMac = ByteArray(32).also(input::readFully)
                status == 1 && MessageDigest.isEqual(
                    receivedMac, acknowledgement(target.secret, ciphertext))
            }
        } finally {
            plain.fill(0)
        }
    }

    internal fun encrypt(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(plain)
    }

    private fun decrypt(key: ByteArray, iv: ByteArray, encrypted: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(encrypted)
    }

    private fun acknowledgement(key: ByteArray, ciphertext: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update("N-LIC-TV-ACK-v1".toByteArray(StandardCharsets.UTF_8))
        mac.update(MessageDigest.getInstance("SHA-256").digest(ciphertext))
        return mac.doFinal()
    }

    /**
     * Never deserialize arbitrary live runtime settings from the sender.
     * Re-parse supported profile links, then atomically merge encrypted data.
     */
    internal fun merge(context: Context, payload: Payload): Int {
        val groups = payload.groups
        val manual = payload.manual
        require(groups.size <= 30 && manual.size <= 500)
        val imported = groups.map { group ->
            require(group.url.length <= 4096 &&
                SubscriptionFetcher.validSubscriptionUrl(group.url))
            val title = group.title
                .filter { it >= ' ' && it != '\u007f' }.trim().take(80)
                .ifEmpty { "TV subscription" }
            require(group.nodes.size <= 500)
            val nodes = group.nodes.mapNotNull {
                SubscriptionImport.parseNode(it.originalUri, title)
            }.distinctBy { it.id }
            require(nodes.isNotEmpty()) { "No supported nodes in subscription" }
            SubscriptionGroup(title, group.url, nodes, System.currentTimeMillis())
        }
        val importedManual = manual.mapNotNull {
            SubscriptionImport.parseNode(it.originalUri, "TV")
        }.distinctBy { it.id }
        require(imported.isNotEmpty() || importedManual.isNotEmpty())
        val old = SubscriptionVault.read(context)
        val urls = imported.map { it.url }.toSet()
        val nextGroups = old.groups.filterNot { it.url in urls } + imported
        val nextManual = (old.manual + importedManual).distinctBy { it.id }
        require(nextGroups.size <= 30)
        val next = old.copy(groups = nextGroups, manual = nextManual)
        require(next.allNodes.size <= 500)
        val selected = old.selectedNodeId?.takeIf { saved ->
            next.allNodes.any { it.id == saved }
        } ?: next.allNodes.firstOrNull()?.id
        SubscriptionVault.write(context, next.copy(selectedNodeId = selected))
        return imported.sumOf { it.nodes.size } + importedManual.size
    }
}
