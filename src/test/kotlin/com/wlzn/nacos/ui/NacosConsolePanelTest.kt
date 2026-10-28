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
import com.intellij.ui.SearchTextField
import javax.swing.JButton
import javax.swing.JList

class NacosConsolePanelTest : BasePlatformTestCase() {
    fun testExplicitRefreshSynchronizesChangedRemoteFormat() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/nacos/v3/auth/user/login") { exchange ->
            val bytes = """{"accessToken":"demo-token"}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/nacos/v3/console/cs/config/list") { exchange ->
            val bytes = """{"code":0,"data":{"totalCount":1,"pageItems":[{"dataId":"demo-config","group":"DEMO_GROUP","type":"properties"}]}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/nacos/v3/console/cs/config") { exchange ->
            val bytes = """{"code":0,"data":{"content":"enabled=true"}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val panel = NacosConsolePanel(project)
        Disposer.register(testRootDisposable, panel)
        try {
            val items = listOf(NacosConfigItem(dataId = "demo-config", group = "DEMO_GROUP", type = "yaml"))
            NacosConsolePanel::class.java.getDeclaredMethod("updateConfigList", List::class.java).apply { isAccessible = true }.invoke(panel, items)
            field<JList<NacosConfigItem>>(panel, "configList").selectedIndex = 0
            assertEquals("yaml", field<NacosConfigEditor>(panel, "contentArea").format)
            NacosConsolePanel::class.java.getDeclaredField("currentEnv").apply { isAccessible = true }
                .set(panel, NacosEnvironment("demo", "http://127.0.0.1:${server.address.port}", "", "demo", "demo"))
            NacosConsolePanel::class.java.getDeclaredMethod("refreshConfigList").apply { isAccessible = true }.invoke(panel)
            PlatformTestUtil.waitWithEventsDispatching("Refreshed type and content should match", {
                val editor = field<NacosConfigEditor>(panel, "contentArea")
                editor.format == "properties" && editor.text == "enabled=true" && editor.isEditable
            }, 10)
            assertEquals("properties", field<javax.swing.JComboBox<*>>(panel, "typeComboBox").selectedItem)
        } finally {
            server.stop(0)
        }
    }

    fun testFilteringKeepsSelectedDraftAndUnknownFormatDoesNotInheritYaml() {
        val panel = NacosConsolePanel(project)
        Disposer.register(testRootDisposable, panel)
        val items = listOf(
            NacosConfigItem(dataId = "demo.yml", group = "DEMO_GROUP", type = "yaml"),
            NacosConfigItem(dataId = "notes", group = "OTHER_GROUP", type = "unknown")
        )
        NacosConsolePanel::class.java.getDeclaredField("allConfigItems").apply { isAccessible = true }.set(panel, items)
        NacosConsolePanel::class.java.getDeclaredMethod("updateConfigList", List::class.java)
            .apply { isAccessible = true }.invoke(panel, items)
        val list = field<JList<NacosConfigItem>>(panel, "configList")
        val content = field<NacosConfigEditor>(panel, "contentArea")
        list.selectedIndex = 0
        content.text = "draft: keep-me\n"
        field<SearchTextField>(panel, "searchField").text = "demo"
        assertEquals("demo.yml", list.selectedValue.dataId)
        assertEquals("draft: keep-me\n", content.text)
        field<SearchTextField>(panel, "searchField").text = ""
        assertEquals("draft: keep-me\n", content.text)
        list.selectedIndex = 1
        assertEquals("text", content.format)
        field<SearchTextField>(panel, "searchField").text = "no-such-configuration"
        assertNull(list.selectedValue)
        assertEquals("", content.text)
        assertFalse(content.isEditable)
        assertFalse(field<JButton>(panel, "publishButton").isEnabled)
    }

    fun testLoadingIsReadOnlyAndLateResponseCannotReplaceSelectedConfig() {
        val releaseFirst = CountDownLatch(1)
        val firstStarted = AtomicBoolean()
        val firstResponded = AtomicBoolean()
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/nacos/v3/console/cs/config/list") { exchange ->
            val bytes = """{"code":0,"data":{"totalCount":0,"pageItems":[]}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
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
            NacosConsolePanel::class.java.getDeclaredMethod("updateConfigList", List::class.java)
                .apply { isAccessible = true }.invoke(panel, items)
            val list = field<JList<NacosConfigItem>>(panel, "configList")
            val content = field<NacosConfigEditor>(panel, "contentArea")
            val publish = field<JButton>(panel, "publishButton")

            list.selectedIndex = 0
            PlatformTestUtil.waitWithEventsDispatching("First request should start", { firstStarted.get() }, 10)
            assertFalse(content.isEditable)
            assertFalse(publish.isEnabled)

            list.selectedIndex = 1
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
