package com.wlzn.nacos.ui

import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.sun.net.httpserver.HttpServer
import com.wlzn.nacos.model.NacosConfigItem
import com.wlzn.nacos.model.NacosEnvironment
import com.wlzn.nacos.service.NacosApiClient
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.table.DefaultTableModel

class NacosHistoryDialogTest : BasePlatformTestCase() {
    fun testClosingHistoryDialogDiscardsLateListResponse() {
        val started = AtomicBoolean()
        val responded = AtomicBoolean()
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/nacos/v3/console/cs/config/list") { exchange ->
            val bytes = """{"code":0,"data":{"pageItems":[]}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/nacos/v3/console/cs/history/list") { exchange ->
            started.set(true)
            release.await(10, TimeUnit.SECONDS)
            val bytes = """{"code":0,"data":{"totalCount":1,"pageItems":[{"id":"7","dataId":"demo.yaml","groupName":"DEMO_GROUP"}]}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            responded.set(true)
        }
        server.start()
        val dialog = NacosHistoryDialog(project, NacosApiClient(),
            NacosEnvironment("demo", "http://127.0.0.1:${server.address.port}", "", "", ""),
            NacosConfigItem(dataId = "demo.yaml", group = "DEMO_GROUP"), "demo-token", "enabled: true", "yaml")
        try {
            PlatformTestUtil.waitWithEventsDispatching("History request should start", { started.get() }, 10)
            dialog.disposeIfNeeded()
            assertTrue(dialog.isDisposed)
            release.countDown()
            PlatformTestUtil.waitWithEventsDispatching("Closed dialog response should complete", { responded.get() }, 10)
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            val model = NacosHistoryDialog::class.java.getDeclaredField("model").apply { isAccessible = true }.get(dialog) as DefaultTableModel
            assertEquals("A disposed dialog must not be repopulated", 0, model.rowCount)
        } finally {
            release.countDown()
            dialog.disposeIfNeeded()
            server.stop(0)
        }
    }

    fun testDiffUsesIndependentReadOnlySnapshotsWithSyntaxType() {
        val request = NacosHistoryDialog.createDiffRequest(project,
            NacosConfigItem(dataId = "demo.yaml", group = "DEMO_GROUP"), "yaml",
            "# previous\nenabled: false\n", "# current draft\nenabled: true\n",
            "版本 7", "当前编辑内容（快照）")
        assertEquals(true, request.getUserData(DiffUserDataKeys.FORCE_READ_ONLY))
        assertTrue(request.getUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS)!!.all { it })
        val left = request.contents[0] as DocumentContent
        val right = request.contents[1] as DocumentContent
        assertNotSame(left.document, right.document)
        assertEquals("# previous\nenabled: false\n", left.document.text)
        assertEquals("# current draft\nenabled: true\n", right.document.text)
        assertEquals("YAML", left.contentType!!.name)
        assertEquals("当前编辑内容（快照）", request.contentTitles[1])
    }
}
