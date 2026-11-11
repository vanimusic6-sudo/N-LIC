package io.github.romanvht.byedpi.subscription

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class XrayXhttpCompilerTest {
    private val auth = LocalSocksAuth("nlic", "b".repeat(48))
    private val uuid = "123e4567-e89b-42d3-a456-426614174000"
    private val key = "A".repeat(43)

    private fun uri(query: String): SubscriptionNode {
        val text = "vless://$uuid@edge.example:443?$query#XHTTP"
        return requireNotNull(SubscriptionImport.parseNode(text))
    }

    private fun compile(node: SubscriptionNode): com.google.gson.JsonObject? {
        val result = XrayConfigCompiler.compile(node, auth)
        if (result !is XrayConfigCompiler.Result.Compiled) return null
        return JsonParser.parseString(result.json).asJsonObject
            .getAsJsonArray("outbounds")[0].asJsonObject
    }

    @Test fun realityXhttpUsesRealTransportAndPreservesServerParameters() {
        val node = uri("type=xhttp&security=reality&sni=cover.example.com&fp=chrome" +
            "&pbk=$key&sid=0123abcd&path=%2Fapi%2Fsync&host=www.example.com&mode=auto")
        assertTrue(XrayXhttpCompiler.isXhttp(node))
        val outbound = requireNotNull(compile(node))
        val stream = outbound.getAsJsonObject("streamSettings")
        assertEquals("xhttp", stream.get("network").asString)
        assertEquals("reality", stream.get("security").asString)
        assertEquals("/api/sync",
            stream.getAsJsonObject("xhttpSettings").get("path").asString)
        assertEquals("www.example.com",
            stream.getAsJsonObject("xhttpSettings").get("host").asString)
        val reality = stream.getAsJsonObject("realitySettings")
        assertEquals("cover.example.com", reality.get("serverName").asString)
        assertEquals(key, reality.get("password").asString)
        assertEquals("0123abcd", reality.get("shortId").asString)
        assertFalse(stream.has("finalmask"))
    }

    @Test fun tlsXhttpKeepsCertificateVerificationAndMode() {
        val node = uri("type=xhttp&security=tls&sni=cdn.example.com" +
            "&path=%2Fpublic%2Fapi&mode=packet-up&alpn=h2" +
            "&extra=%7B%22xPaddingBytes%22%3A%22100-1000%22%7D")
        val stream = requireNotNull(compile(node)).getAsJsonObject("streamSettings")
        val tls = stream.getAsJsonObject("tlsSettings")
        assertEquals(false, tls.get("allowInsecure").asBoolean)
        assertEquals("cdn.example.com", tls.get("serverName").asString)
        assertEquals("packet-up", stream.getAsJsonObject("xhttpSettings").get("mode").asString)
        assertEquals("100-1000", stream.getAsJsonObject("xhttpSettings")
            .getAsJsonObject("extra").get("xPaddingBytes").asString)
    }

    @Test fun rejectedUnsafeOrMismatchedConfigurations() {
        val base = "type=xhttp&security=reality&sni=cover.example.com&pbk=$key"
        assertNull(compile(uri(base + "&insecure=1")))
        assertNull(compile(uri(base + "&allowInsecure=true")))
        assertNull(compile(uri(base + "&mode=fake")))
        assertNull(compile(uri(base + "&pbk=broken")))
        assertNull(compile(uri(base + "&sid=123")))
        assertNull(compile(uri(base + "&path=not-an-http-path")))
        assertNull(compile(uri(base + "&flow=xtls-rprx-vision")))
        assertNull(compile(uri(base + "&extra=%7B%22downloadSettings%22%3A%7B%7D%7D")))
        assertNull(compile(uri(base + "&extra=%7B%22xPaddingBytes%22%3A%220-9000%22%7D")))
        assertNull(compile(uri("type=xhttp&security=none&path=%2F")))
    }

    @Test fun sanitizedJsonXhttpWorksWithoutSingBoxTranslation() {
        val profile = """
          {
            "outbounds": [{
              "type":"vless", "tag":"XHTTP JSON", "server":"edge.example",
              "server_port":443, "uuid":"$uuid", "flow":"",
              "transport":{"type":"xhttp", "path":"/bridge", "mode":"stream-one"},
              "tls":{"enabled":true, "server_name":"cdn.example.com"}
            }]
          }
        """.trimIndent()
        val node = VPNFileImport.parse("test.json", profile).single()
        assertTrue(XrayXhttpCompiler.isXhttp(node))
        val stream = requireNotNull(compile(node)).getAsJsonObject("streamSettings")
        assertEquals("xhttp", stream.get("network").asString)
        assertEquals("/bridge", stream.getAsJsonObject("xhttpSettings").get("path").asString)
        assertEquals("tls", stream.get("security").asString)
    }

    @Test fun nativeXrayJsonImportPreservesOnlyExplicitXhttpOutbound() {
        val profile = """
          {
            "inbounds":[{"protocol":"socks","listen":"0.0.0.0","port":30000}],
            "outbounds":[{
              "protocol":"vless","tag":"Provider XHTTP",
              "settings":{"vnext":[{
                "address":"edge.example","port":443,
                "users":[{"id":"$uuid","encryption":"none"}]
              }]},
              "streamSettings":{
                "network":"xhttp","security":"reality",
                "xhttpSettings":{"path":"/protected","mode":"auto"},
                "realitySettings":{
                  "serverName":"cover.example.com","fingerprint":"chrome",
                  "password":"$key","shortId":""
                }
              }
            }],
            "routing":{"rules":[{"type":"field","outboundTag":"direct"}]}
          }
        """.trimIndent()
        val imported = VPNFileImport.parse("provider.json", profile)
        assertEquals(1, imported.size)
        val stream = requireNotNull(compile(imported.single()))
            .getAsJsonObject("streamSettings")
        assertEquals("xhttp", stream.get("network").asString)
        assertEquals("/protected", stream.getAsJsonObject("xhttpSettings")
            .get("path").asString)
        assertEquals("reality", stream.get("security").asString)
    }

    @Test fun nativeXrayJsonRejectsInsecureOrDynamicDownloadRouting() {
        val insecure = """
          {"outbounds":[{"protocol":"vless",
            "settings":{"vnext":[{"address":"edge.example","port":443,
              "users":[{"id":"$uuid"}]}]},
            "streamSettings":{"network":"xhttp","security":"tls",
              "xhttpSettings":{"path":"/"},
              "tlsSettings":{"serverName":"edge.example","allowInsecure":true}}
          }]}
        """.trimIndent()
        assertTrue(runCatching { VPNFileImport.parse("provider.json", insecure) }
            .getOrDefault(emptyList()).isEmpty())
        val withDownload = """
          {"outbounds":[{"protocol":"vless",
            "settings":{"vnext":[{"address":"edge.example","port":443,
              "users":[{"id":"$uuid"}]}]},
            "streamSettings":{"network":"xhttp","security":"tls",
              "xhttpSettings":{"path":"/",
                "downloadSettings":{"address":"unknown.example"}},
              "tlsSettings":{"serverName":"edge.example"}}
          }]}
        """.trimIndent()
        assertTrue(runCatching { VPNFileImport.parse("provider.json", withDownload) }
            .getOrDefault(emptyList()).isEmpty())
    }

    @Test fun rawRealityProfileIsNotSilentlyConvertedToXhttp() {
        val node = uri("type=tcp&security=reality&sni=cover.example.com&pbk=$key")
        assertFalse(XrayXhttpCompiler.isXhttp(node))
        assertNotNull(compile(node))
        val stream = requireNotNull(compile(node)).getAsJsonObject("streamSettings")
        assertEquals("raw", stream.get("network").asString)
    }

    @Test fun autoPortfolioKeepsXhttpAlongsideRawVless() {
        val raw = uri("type=tcp&security=reality&sni=cover.example.com&pbk=$key")
        val xhttp = uri("type=xhttp&security=reality&sni=cover.example.com&pbk=$key")
        val nodes = listOf(raw, xhttp)
        val chosen = XrayAutoSelector.candidates(nodes, raw.id, false) {
            compile(it) != null
        }
        assertEquals(2, chosen.size)
        assertEquals(listOf(raw.id, xhttp.id), chosen.map { it.id })
    }
}
