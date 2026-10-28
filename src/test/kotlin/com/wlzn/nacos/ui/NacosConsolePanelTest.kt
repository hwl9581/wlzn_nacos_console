package com.wlzn.nacos.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import com.wlzn.nacos.model.NacosConfigItem
import com.wlzn.nacos.model.NacosEnvironment
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JButton
import javax.swing.JTable
import javax.swing.JTextArea

class NacosConsolePanelTest : BasePlatformTestCase() {
    fun testLoadingIsReadOnlyAndLateResponseCannotReplaceSelectedConfig() {
        val releaseFirst = CountDownLatch(1)
        val firstStarted = AtomicBoolean()
        val firstResponded = AtomicBoolean()
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/nacos/v3/console/cs/config") { exchange ->
            val first = exchange.requestURI.rawQuery.contains("dataId=first.yml")
            if (first) {
                firstStarted.set(true)
                releaseFirst.await(10, TimeUnit.SECONDS)
            }
            val content = if (first) "first content" else "second content"
            val bytes = """{"code":0,"data":{"content":"$content"}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            if (first) firstResponded.set(true)
        }
        server.start()
        val panel = NacosConsolePanel(project)
        Disposer.register(testRootDisposable, panel)
        try {
            val environment = NacosEnvironment("test", "http://127.0.0.1:${server.address.port}", "", "user", "pass")
            NacosConsolePanel::class.java.getDeclaredField("currentEnv").apply { isAccessible = true }.set(panel, environment)
            val items = listOf(
                NacosConfigItem(dataId = "first.yml", group = "GROUP", type = "yaml"),
                NacosConfigItem(dataId = "second.yml", group = "GROUP", type = "yaml")
            )
            NacosConsolePanel::class.java.getDeclaredMethod("updateConfigTable", List::class.java)
                .apply { isAccessible = true }.invoke(panel, items)
            val table = field<JTable>(panel, "configTable")
            val content = field<JTextArea>(panel, "contentArea")
            val publish = field<JButton>(panel, "publishButton")

            table.setRowSelectionInterval(0, 0)
            PlatformTestUtil.waitWithEventsDispatching("First request should start", { firstStarted.get() }, 10)
            assertFalse(content.isEditable)
            assertFalse(publish.isEnabled)

            table.setRowSelectionInterval(1, 1)
            PlatformTestUtil.waitWithEventsDispatching("Selected config should load", { content.text == "second content" }, 10)
            assertTrue(content.isEditable)
            assertTrue(publish.isEnabled)
            content.text = "edited second content"

            releaseFirst.countDown()
            val waitUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
            PlatformTestUtil.waitWithEventsDispatching("First response should be ignored", {
                firstResponded.get() && System.nanoTime() >= waitUntil
            }, 10)
            assertEquals("edited second content", content.text)
            assertTrue(content.isEditable)
        } finally {
            releaseFirst.countDown()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private inline fun <reified T> field(panel: NacosConsolePanel, name: String): T =
        NacosConsolePanel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(panel) as T
}
