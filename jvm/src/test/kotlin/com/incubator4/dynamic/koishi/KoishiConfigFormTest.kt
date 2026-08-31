package com.incubator4.dynamic.koishi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.colter.dynamic.core.config.ConfigFieldType

class KoishiConfigFormTest {
    @Test
    fun `form should expose connection fields without bot tokens or account ids`() {
        val paths = KoishiConfigForm.spec.fields.map { it.path }
        assertEquals(listOf("mode", "url", "host", "port", "accessToken", "reconnect"), paths)
        assertEquals(setOf("连接"), KoishiConfigForm.spec.fields.map { it.section }.toSet())

        val tokenField = KoishiConfigForm.spec.fields.single { it.path == "accessToken" }
        assertEquals(ConfigFieldType.SECRET, tokenField.type)
        assertTrue(tokenField.secret)
        assertTrue(tokenField.restartRequired)

        val urlField = KoishiConfigForm.spec.fields.single { it.path == "url" }
        assertEquals(listOf(KoishiConnectionMode.FORWARD_WS.name), urlField.visibleWhen?.values)
        assertTrue(urlField.description.contains("bots.list"))

        val hostField = KoishiConfigForm.spec.fields.single { it.path == "host" }
        assertEquals(listOf(KoishiConnectionMode.REVERSE_WS.name), hostField.visibleWhen?.values)

        assertFalse(KoishiConfigForm.spec.fields.any { it.path.contains("account", ignoreCase = true) })
    }

    @Test
    fun `default config should use forward websocket without token`() {
        val config = KoishiConfig()
        assertEquals(KoishiConnectionMode.FORWARD_WS, config.mode)
        assertEquals("ws://127.0.0.1:9800", config.url)
        assertEquals("127.0.0.1", config.host)
        assertEquals(9800, config.port)
        assertEquals("", config.accessToken)
        assertTrue(config.reconnect)
    }

    @Test
    fun `plugin apply should validate and request restart`() {
        val plugin = KoishiGatewayPlugin()

        val result = plugin.applyConfig(
            KoishiConfig(url = "ws://127.0.0.1:9801", accessToken = " secret "),
        )

        assertTrue(result.changed)
        assertTrue(result.restartRequired)
        assertEquals(listOf("Koishi 插件"), result.restartTargets)
        assertEquals("ws://127.0.0.1:9801", plugin.currentConfig().url)
        assertEquals("secret", plugin.currentConfig().accessToken)

        val error = assertFailsWith<IllegalArgumentException> {
            plugin.applyConfig(KoishiConfig(url = "http://127.0.0.1:9800"))
        }
        assertEquals("Koishi 连接地址必须是 ws:// 或 wss://", error.message)
    }

    @Test
    fun `config validate should reject invalid forward and reverse settings`() {
        KoishiConfigForm.validate(KoishiConfig())

        val blank = assertFailsWith<IllegalArgumentException> {
            KoishiConfigForm.validate(KoishiConfig(url = " "))
        }
        assertEquals("Koishi 连接地址不能为空", blank.message)

        val http = assertFailsWith<IllegalArgumentException> {
            KoishiConfigForm.validate(KoishiConfig(url = "https://127.0.0.1:9800"))
        }
        assertEquals("Koishi 连接地址必须是 ws:// 或 wss://", http.message)

        val publicBind = assertFailsWith<IllegalArgumentException> {
            KoishiConfigForm.validate(
                KoishiConfig(
                    mode = KoishiConnectionMode.REVERSE_WS,
                    host = "0.0.0.0",
                    accessToken = "",
                ),
            )
        }
        assertEquals("反向 WebSocket 监听非本地地址时必须配置 Token", publicBind.message)

        KoishiConfigForm.validate(
            KoishiConfig(
                mode = KoishiConnectionMode.REVERSE_WS,
                host = "0.0.0.0",
                accessToken = "token",
            ),
        )
    }
}
