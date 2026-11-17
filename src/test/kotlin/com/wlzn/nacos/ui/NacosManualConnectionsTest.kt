package com.wlzn.nacos.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.sun.net.httpserver.HttpServer
import com.wlzn.nacos.config.ManualNacosConnection
import com.wlzn.nacos.config.NacosConnectionAccess
import com.wlzn.nacos.config.NacosConnectionSettings
import com.wlzn.nacos.config.NacosConnectionSettingsTest.MemoryPasswords
import com.wlzn.nacos.model.NacosEnvironment
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.*
import java.awt.Container
import java.awt.event.ComponentEvent
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO

class NacosManualConnectionsTest : BasePlatformTestCase() {
    fun testSavedManualConnectionWorksWithoutProjectConfigurationAndPublishesWithFreshToken() {
        val fixture = Fixture()
        val settings = NacosConnectionSettings()
        val access = NacosConnectionAccess(settings, MemoryPasswords())
        val saved = access.save(profile(fixture.address), "unit-password", null).connection
        settings.rememberSelection(true, saved.id)
        val panel = panel(settings, access)
        try {
            field<JButton>(panel, "connectButton").doClick()
            waitFor("manual connection") { field<NacosEnvironment?>(panel, "currentEnv") != null }
            assertEquals(saved.id, field<JComboBox<ManualNacosConnection>>(panel, "manualComboBox").selectedItem.let { (it as ManualNacosConnection).id })
            assertEquals(0, field<JComboBox<*>>(panel, "configFileComboBox").itemCount)
            field<JList<*>>(panel, "configList").selectedIndex = 0
            val editor = field<NacosConfigEditor>(panel, "contentArea")
            waitFor("manual config content") { editor.isEditable && editor.text == "enabled: true" }
            editor.text = "enabled: false"
            val oldDialog = TestDialogManager.setTestDialog { Messages.YES }
            try { field<JButton>(panel, "publishButton").doClick() }
            finally { TestDialogManager.setTestDialog(oldDialog) }
            waitFor("manual config published") { fixture.posts.isNotEmpty() && editor.isEditable }
            assertEquals(1, fixture.posts.size)
            assertEquals("enabled: false", fixture.posts.single()["content"])
            assertEquals("token-2", fixture.posts.single()["accessToken"])
            assertEquals("public", fixture.posts.single()["namespaceId"])
            assertEquals("unit-password", fixture.logins.first()["password"])
            assertEquals("", settings.find(saved.id)!!.namespace)
        } finally { Disposer.dispose(panel); fixture.close() }
    }

    fun testGlobalEditUpdatesOtherPanelsAndKeepsUnpublishedDraftReadOnly() {
        val fixture = Fixture()
        val settings = NacosConnectionSettings()
        val access = NacosConnectionAccess(settings, MemoryPasswords())
        val saved = access.save(profile(fixture.address), "unit-password", null).connection
        settings.rememberSelection(true, saved.id)
        val first = panel(settings, access)
        val second = panel(settings, access)
        try {
            field<JButton>(first, "connectButton").doClick()
            waitFor("connected first panel") { field<NacosEnvironment?>(first, "currentEnv") != null }
            field<JList<*>>(first, "configList").selectedIndex = 0
            val editor = field<NacosConfigEditor>(first, "contentArea")
            waitFor("loaded draft baseline") { editor.isEditable }
            editor.text = "draft: retain-me"
            val changed = access.save(saved.copy(name = "Updated connection", namespace = "new-namespace"), "new-password", saved).connection
            waitFor("all projects observe edit") {
                field<NacosEnvironment?>(first, "currentEnv") == null &&
                    (field<JComboBox<*>>(second, "manualComboBox").selectedItem as? ManualNacosConnection) == changed
            }
            assertEquals("draft: retain-me", editor.text)
            assertFalse(editor.isEditable)
            assertFalse(field<JButton>(first, "publishButton").isEnabled)
            assertFalse(field<JButton>(first, "fetchLatestButton").isEnabled)
            assertTrue(field<JButton>(first, "connectButton").isEnabled)
            field<JButton>(second, "connectButton").doClick()
            waitFor("second panel uses changed profile") { field<NacosEnvironment?>(second, "currentEnv")?.namespace == "new-namespace" }
            assertEquals("new-password", fixture.logins.last()["password"])
            access.remove(changed)
            waitFor("deletion removes all global selections") { field<JComboBox<*>>(first, "manualComboBox").itemCount == 0 && field<NacosEnvironment?>(second, "currentEnv") == null }
            assertEquals("draft: retain-me", editor.text)
        } finally { Disposer.dispose(first); Disposer.dispose(second); fixture.close() }
    }

    fun testConnectionChangeDuringLoginDoesNotShowOutdatedSuccess() {
        val responseSent = CountDownLatch(1)
        val fixture = Fixture(responseSent)
        val settings = NacosConnectionSettings()
        val access = NacosConnectionAccess(settings, MemoryPasswords())
        val original = access.save(profile(fixture.address), "unit-password", null).connection
        settings.rememberSelection(true, original.id)
        val panel = panel(settings, access)
        val checked = AtomicBoolean()
        val acceptedOld = AtomicBoolean()
        try {
            ApplicationManager.getApplication().invokeLater({
                // 完成回调可能已经排队；在变更通知处理前观察它是否接受了旧快照。
                ApplicationManager.getApplication().invokeLater({
                    acceptedOld.set(field<NacosEnvironment?>(panel, "currentEnv") != null)
                    checked.set(true)
                })
                access.save(original.copy(namespace = "changed-before-callback"), "new-password", original)
            })
            field<JButton>(panel, "connectButton").doClick()
            assertTrue(responseSent.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
            waitFor("queued completion settles") { checked.get() && System.nanoTime() >= deadline && field<JButton>(panel, "connectButton").isEnabled }
            assertFalse(acceptedOld.get())
            assertNull(field<NacosEnvironment?>(panel, "currentEnv"))
            assertFalse(field<JButton>(panel, "refreshButton").isEnabled)
        } finally { Disposer.dispose(panel); fixture.close() }
    }

    fun testEdtCompletionGateRechecksSnapshotAfterBackgroundValidation() {
        val settings = NacosConnectionSettings()
        val access = NacosConnectionAccess(settings, MemoryPasswords())
        val original = access.save(profile("https://nacos.example.com/nacos"), "unit-password", null).connection
        settings.rememberSelection(true, original.id)
        val panel = panel(settings, access)
        val completed = AtomicBoolean()
        val accepted = AtomicBoolean(true)
        try {
            ApplicationManager.getApplication().executeOnPooledThread<Unit> {
                access.environment(original)
            }.get(5, TimeUnit.SECONDS)
            val generation = field<Int>(panel, "connectionId")
            ApplicationManager.getApplication().invokeLater({
                val gate = NacosConsolePanel::class.java.getDeclaredMethod("connectionUnchanged", Int::class.javaPrimitiveType, ManualNacosConnection::class.java).apply { isAccessible = true }
                accepted.set(gate.invoke(panel, generation, original) as Boolean)
                completed.set(true)
            })
            // 后台已经校验成功、EDT 回调已经排队；通知尚未分发，generation 仍相同。
            settings.save(original.copy(namespace = "changed-after-background-validation"), original)
            assertEquals(generation, field<Int>(panel, "connectionId"))
            waitFor("EDT completion checks current metadata") { completed.get() }
            assertFalse(accepted.get())
            assertNull(field<NacosEnvironment?>(panel, "currentEnv"))
            assertTrue(field<JButton>(panel, "connectButton").isEnabled)
        } finally { Disposer.dispose(panel) }
    }

    fun testProfileChangeDuringPublishRetainsDraftAndReportsCompletedWriteOnce() {
        val publishStarted = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val fixture = Fixture(publishStarted = publishStarted, releasePublish = releasePublish)
        val settings = NacosConnectionSettings()
        val access = NacosConnectionAccess(settings, MemoryPasswords())
        val original = access.save(profile(fixture.address), "unit-password", null).connection
        settings.rememberSelection(true, original.id)
        val panel = panel(settings, access)
        val notifications = CopyOnWriteArrayList<String>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) { if (notification.groupId == "Nacos Console") notifications.add(notification.content) }
        })
        try {
            field<JButton>(panel, "connectButton").doClick()
            waitFor("manual connection") { field<NacosEnvironment?>(panel, "currentEnv") != null }
            field<JList<*>>(panel, "configList").selectedIndex = 0
            val editor = field<NacosConfigEditor>(panel, "contentArea")
            waitFor("editable config") { editor.isEditable }
            editor.text = "draft: publish-once"
            val previousDialog = TestDialogManager.setTestDialog { Messages.YES }
            try { field<JButton>(panel, "publishButton").doClick() }
            finally { TestDialogManager.setTestDialog(previousDialog) }
            waitFor("publish request in flight") { publishStarted.count == 0L }
            access.save(original.copy(namespace = "other-namespace"), "new-password", original)
            waitFor("profile change invalidates connection") { field<NacosEnvironment?>(panel, "currentEnv") == null }
            releasePublish.countDown()
            waitFor("completed write reports original target") { notifications.any { it.contains("发布成功") && it.contains("连接配置已变化") } }
            assertEquals(1, fixture.posts.size)
            assertEquals("public", fixture.posts.single()["namespaceId"])
            assertEquals("draft: publish-once", editor.text)
            assertFalse(editor.isEditable)
            assertFalse(field<JButton>(panel, "publishButton").isEnabled)
        } finally { releasePublish.countDown(); Disposer.dispose(panel); fixture.close() }
    }

    fun testNativeManualControlsFitAndPasswordRemainsMasked() {
        val settings = NacosConnectionSettings()
        val access = NacosConnectionAccess(settings, MemoryPasswords())
        val saved = access.save(profile("https://nacos.example.com/nacos"), "unit-password", null).connection
        settings.rememberSelection(true, saved.id)
        val dialog = NacosConnectionsDialog(project, saved.id, settings, access)
        val panel = panel(settings, access)
        try {
            assertTrue(field<JPasswordField>(dialog, "password").password.isEmpty())
            assertTrue(field<JPasswordField>(dialog, "password").echoChar != 0.toChar())
            var form: Container = field<JTextField>(dialog, "connectionName")
            while (form.parent != null) form = form.parent
            render(form, "dialog", 780, 540)
            for (name in listOf("connectionName", "address", "namespace", "username", "password")) {
                val input = field<JTextField>(dialog, name)
                assertTrue("Field $name must be usable", input.width >= 120)
                assertTrue("Field $name must fit its parent", input.x + input.width <= input.parent.width)
            }
            render(panel, "console", 1100, 580)
            render(panel, "console-narrow", 720, 580)
            val connect = field<JButton>(panel, "connectButton")
            assertTrue(connect.x >= 0 && connect.x + connect.width <= connect.parent.width)
            assertTrue(field<JButton>(panel, "manageConnectionsButton").isVisible)
        } finally { dialog.disposeIfNeeded(); Disposer.dispose(panel) }
    }

    fun testInvalidSaveKeepsDialogOpenAndCorrectionCanBeSaved() {
        val settings = NacosConnectionSettings()
        val access = NacosConnectionAccess(settings, MemoryPasswords())
        val dialog = NacosConnectionsDialog(project, "", settings, access)
        try {
            field<JTextField>(dialog, "connectionName").text = "demo"
            field<JTextField>(dialog, "address").text = "http://localhost:8848/nacos"
            field<JTextField>(dialog, "username").text = "demo"
            field<JPasswordField>(dialog, "password").text = "unit-password"
            assertTrue(dialog.performValidateAll().isEmpty())
            dialog.isOKActionEnabled = true
            field<JTextField>(dialog, "connectionName").text = ""
            invoke(dialog, "doOKAction")
            assertFalse(dialog.isDisposed)
            assertTrue(settings.connections().isEmpty())
            field<JTextField>(dialog, "connectionName").text = "recovered"
            invoke(dialog, "doOKAction")
            waitFor("corrected connection saved") { dialog.isDisposed }
            assertTrue(dialog.isOK)
            assertEquals("recovered", settings.connections().single().name)
        } finally { dialog.disposeIfNeeded() }
    }

    fun testDialogConnectionTestIsReadOnlyAndNamespaceBlankMeansPublic() {
        val fixture = Fixture()
        val settings = NacosConnectionSettings()
        val access = NacosConnectionAccess(settings, MemoryPasswords())
        val dialog = NacosConnectionsDialog(project, "", settings, access)
        try {
            field<JTextField>(dialog, "connectionName").text = "demo"
            field<JTextField>(dialog, "address").text = fixture.address
            field<JTextField>(dialog, "username").text = "demo"
            field<JPasswordField>(dialog, "password").text = "unit-password"
            invoke(dialog, "testConnection")
            waitFor("read-only validation complete") { field<JLabel>(dialog, "status").text.startsWith("验证成功") }
            assertTrue(settings.connections().isEmpty())
            assertTrue(fixture.posts.isEmpty())
            assertTrue(fixture.lists.isNotEmpty())
            assertEquals("public", fixture.lists.last()["namespaceId"])
            assertFalse(dialog.isDisposed)
            assertTrue(field<JTextField>(dialog, "address").isEnabled)
        } finally { dialog.disposeIfNeeded(); fixture.close() }
    }

    private fun panel(settings: NacosConnectionSettings, access: NacosConnectionAccess) = NacosConsolePanel(project, settings, access)
    private fun render(component: Container, name: String, width: Int, height: Int) {
        fun layout(container: Container) { container.doLayout(); container.components.filterIsInstance<Container>().forEach(::layout) }
        component.setSize(width, height)
        repeat(3) { layout(component) }
        fun resized(container: Container) { container.dispatchEvent(ComponentEvent(container, ComponentEvent.COMPONENT_RESIZED)); container.components.filterIsInstance<Container>().forEach(::resized) }
        resized(component)
        repeat(3) { layout(component) }
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try { component.printAll(graphics) } finally { graphics.dispose() }
        ImageIO.write(image, "png", Path.of(System.getProperty("java.io.tmpdir"), "wlzn-nacos-manual-$name.png").toFile())
    }
    private fun profile(address: String) = ManualNacosConnection(name = "Manual test", serverAddr = address, username = "demo")
    private fun waitFor(message: String, condition: () -> Boolean) = PlatformTestUtil.waitWithEventsDispatching(message, condition, 10)
    private fun invoke(target: Any, name: String) { target.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(target) }
    private inline fun <reified T> field(target: Any, name: String): T = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as T

    private class Fixture(
        private val responseSent: CountDownLatch? = null,
        private val publishStarted: CountDownLatch? = null,
        private val releasePublish: CountDownLatch? = null
    ) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val address = "http://127.0.0.1:${server.address.port}/nacos"
        val logins = CopyOnWriteArrayList<Map<String, String>>()
        val lists = CopyOnWriteArrayList<Map<String, String>>()
        val posts = CopyOnWriteArrayList<Map<String, String>>()
        private val content = AtomicReference("enabled: true")
        init {
            server.createContext("/") { exchange ->
                val raw = if (exchange.requestMethod == "GET") exchange.requestURI.rawQuery.orEmpty() else exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val parameters = raw.split('&').filter { it.isNotEmpty() }.associate { URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
                val response = when {
                    exchange.requestURI.path.endsWith("/auth/user/login") -> { logins.add(parameters); """{"accessToken":"token-${logins.size}"}""" }
                    exchange.requestURI.path.endsWith("/cs/config/list") -> { lists.add(parameters); """{"code":0,"data":{"totalCount":1,"pageItems":[{"dataId":"demo.yaml","group":"DEMO_GROUP","type":"yaml"}]}}""" }
                    exchange.requestURI.path.endsWith("/cs/config") && exchange.requestMethod == "POST" -> {
                        posts.add(parameters)
                        content.set(parameters.getValue("content"))
                        publishStarted?.countDown()
                        releasePublish?.await(10, TimeUnit.SECONDS)
                        """{"code":0,"data":true}"""
                    }
                    exchange.requestURI.path.endsWith("/cs/config") -> com.google.gson.Gson().toJson(mapOf("code" to 0, "data" to mapOf("content" to content.get())))
                    else -> "{}"
                }
                val bytes = response.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                if (exchange.requestURI.path.endsWith("/cs/config/list")) responseSent?.countDown()
            }
            server.start()
        }
        override fun close() { server.stop(0) }
    }
}
