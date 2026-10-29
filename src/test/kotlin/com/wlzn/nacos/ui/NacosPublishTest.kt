package com.wlzn.nacos.ui

import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import com.wlzn.nacos.model.NacosConfigItem
import com.wlzn.nacos.model.NacosEnvironment
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.JList

class NacosPublishTest : BasePlatformTestCase() {
    fun testExpiredCachedTokenIsReplacedBeforePublishingExactlyOnce() = checkPublish(200, 200)
    fun testLoginFailureDoesNotSendConfigurationAndKeepsDraft() = checkPublish(403, 200)
    fun testForbiddenPublishDoesNotRetryOrDiscardDraft() = checkPublish(200, 403)
    fun testServerFailureDoesNotRetryPublication() = checkPublish(200, 500)
    fun testCancelDoesNotLoginOrPublish() = checkPublish(200, 200, cancel = true)
    fun testClosingPanelDuringLoginPreventsPublication() = checkPublish(200, 200, closeDuringLogin = true)

    private fun checkPublish(loginStatus: Int, publishStatus: Int, cancel: Boolean = false, closeDuringLogin: Boolean = false) {
        val logins = AtomicInteger()
        val writes = AtomicInteger()
        val loginResponded = AtomicBoolean()
        val releaseLogin = CountDownLatch(if (closeDuringLogin) 1 else 0)
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val submitted = Collections.synchronizedList(mutableListOf<Map<String, String>>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body = exchange.requestBody.use { String(it.readBytes(), Charsets.UTF_8) }
            requests.add("${exchange.requestMethod} $path")
            val (status, response) = when {
                path == "/nacos/v3/auth/user/login" -> {
                    logins.incrementAndGet()
                    releaseLogin.await(10, TimeUnit.SECONDS)
                    loginStatus to if (loginStatus == 200) """{"accessToken":"fresh-demo-token","tokenTtl":18000}""" else """{"message":"login denied"}"""
                }
                path == "/nacos/v3/admin/cs/config/list" -> 200 to """{"code":0,"data":{"totalCount":1,"pageItems":[{"dataId":"demo.yaml","groupName":"DEMO_GROUP","type":"yaml"}]}}"""
                path == "/nacos/v3/admin/cs/config" && exchange.requestMethod == "POST" -> {
                    writes.incrementAndGet()
                    submitted.add(body.split('&').associate {
                        URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
                    })
                    publishStatus to if (publishStatus == 200) """{"code":0,"data":true}""" else """{"message":"denied"}"""
                }
                path == "/nacos/v3/admin/cs/config" -> 200 to """{"code":0,"data":{"content":"enabled: true\n"}}"""
                else -> 404 to "Not Found"
            }
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            if (path == "/nacos/v3/auth/user/login") loginResponded.set(true)
        }
        server.start()
        val panel = NacosConsolePanel(project)
        Disposer.register(testRootDisposable, panel)
        val previousDialog = TestDialogManager.setTestDialog(if (cancel) TestDialog.NO else TestDialog.YES)
        try {
            val item = NacosConfigItem(dataId = "demo.yaml", group = "DEMO_GROUP", type = "yaml")
            NacosConsolePanel::class.java.getDeclaredMethod("updateConfigList", List::class.java).apply { isAccessible = true }.invoke(panel, listOf(item))
            field<JList<NacosConfigItem>>(panel, "configList").selectedIndex = 0
            val content = field<NacosConfigEditor>(panel, "contentArea")
            content.text = "# edited draft\nenabled: true\n"
            content.isEditable = true
            NacosConsolePanel::class.java.getDeclaredField("currentEnv").apply { isAccessible = true }.set(panel,
                NacosEnvironment("demo", "http://127.0.0.1:${server.address.port}", "demo-namespace", "demo-user", "demo-password"))
            NacosConsolePanel::class.java.getDeclaredField("currentToken").apply { isAccessible = true }.set(panel, "expired-demo-token")
            NacosConsolePanel::class.java.getDeclaredField("contentLoaded").apply { isAccessible = true }.set(panel, true)
            NacosConsolePanel::class.java.getDeclaredMethod("publishConfig").apply { isAccessible = true }.invoke(panel)
            if (cancel) {
                assertEquals(0, logins.get())
                assertEquals(0, writes.get())
                return
            }
            if (closeDuringLogin) {
                PlatformTestUtil.waitWithEventsDispatching("Login should begin", { logins.get() > 0 }, 10)
                Disposer.dispose(panel)
                releaseLogin.countDown()
                val after = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300)
                PlatformTestUtil.waitWithEventsDispatching("Disposed panel should stop publication", { loginResponded.get() && System.nanoTime() >= after }, 10)
                assertEquals(0, writes.get())
                return
            }
            PlatformTestUtil.waitWithEventsDispatching("Publication flow should complete", {
                logins.get() > 0 && field<JButton>(panel, "publishButton").isEnabled
            }, 10)
            if (loginStatus != 200) {
                assertEquals(0, writes.get())
            } else {
                assertEquals(1, writes.get())
                assertEquals("POST /nacos/v3/auth/user/login", requests.first())
                assertEquals("fresh-demo-token", submitted.single()["accessToken"])
                assertEquals("demo.yaml", submitted.single()["dataId"])
                assertEquals("DEMO_GROUP", submitted.single()["groupName"])
                assertEquals("demo-namespace", submitted.single()["namespaceId"])
                assertEquals("# edited draft\nenabled: true\n", submitted.single()["content"])
                assertEquals("yaml", submitted.single()["type"])
            }
            if (loginStatus != 200 || publishStatus != 200) {
                assertEquals("# edited draft\nenabled: true\n", content.text)
                assertTrue(content.isEditable)
            }
        } finally {
            TestDialogManager.setTestDialog(previousDialog)
            releaseLogin.countDown()
            server.stop(0)
        }
    }

    private inline fun <reified T> field(panel: NacosConsolePanel, name: String): T =
        NacosConsolePanel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(panel) as T
}
