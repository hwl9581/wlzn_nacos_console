package com.wlzn.nacos.config

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.openapi.util.JDOMUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NacosConnectionSettingsTest : BasePlatformTestCase() {
    fun testStateRoundTripIsGlobalAndDoesNotSerializePassword() {
        assertSame(NacosConnectionSettings.getInstance(), NacosConnectionSettings.getInstance())
        val settings = NacosConnectionSettings()
        val passwords = MemoryPasswords()
        val access = NacosConnectionAccess(settings, passwords)
        val saved = access.save(profile(), "unit-secret-value", null).connection
        settings.rememberSelection(true, saved.id)
        val xml = JDOMUtil.writeElement(XmlSerializer.serialize(settings.state))
        assertFalse(xml.contains("unit-secret-value"))
        assertFalse(xml.contains("accessToken"))
        val restored = NacosConnectionSettings()
        restored.loadState(XmlSerializer.deserialize(JDOMUtil.load(xml), NacosConnectionSettings.State::class.java))
        assertEquals(saved, restored.find(saved.id))
        assertTrue(restored.state.manualMode)
        assertEquals(saved.id, restored.state.selectedConnectionId)
        assertEquals("unit-secret-value", NacosConnectionAccess(restored, passwords).environment(saved).password)
        settings.state.connections[0].name = "mutated"
        settings.connections()[0].serverAddr = "mutated"
        assertEquals(saved, settings.find(saved.id))
    }

    fun testPasswordRetentionReplacementAndStaleSaveAreConsistent() {
        val settings = NacosConnectionSettings()
        val passwords = MemoryPasswords()
        val access = NacosConnectionAccess(settings, passwords)
        val first = access.save(profile(), "first-secret", null).connection
        val renamed = access.save(first.copy(name = "renamed"), null, first).connection
        assertEquals(first.passwordKey, renamed.passwordKey)
        assertEquals("first-secret", access.environment(renamed).password)
        val replaced = access.save(renamed, "second-secret", renamed).connection
        assertFalse(replaced.passwordKey == renamed.passwordKey)
        assertNull(passwords.values[first.passwordKey])
        assertEquals("second-secret", access.environment(replaced).password)
        assertFails { access.save(renamed.copy(name = "stale"), "stale-secret", renamed) }
        assertEquals(setOf(replaced.passwordKey), passwords.values.keys)
        assertEquals(replaced, settings.find(replaced.id))
        assertFails { access.environment(renamed) }
        assertTrue(access.remove(replaced))
        assertTrue(settings.connections().isEmpty())
        assertTrue(passwords.values.isEmpty())
    }

    fun testCredentialWriteFailureDoesNotSaveMetadataOrOverwriteExistingSecret() {
        val settings = NacosConnectionSettings()
        val passwords = MemoryPasswords()
        val access = NacosConnectionAccess(settings, passwords)
        val original = access.save(profile(), "original-secret", null).connection
        passwords.failWrite = true
        assertFails { access.save(original.copy(name = "failed"), "new-secret", original) }
        assertEquals(original, settings.find(original.id))
        assertEquals("original-secret", access.environment(original).password)
    }

    fun testConcurrentPasswordEditsOnlyCommitOneMatchingSnapshot() {
        val settings = NacosConnectionSettings()
        val passwords = MemoryPasswords()
        val access = NacosConnectionAccess(settings, passwords)
        val original = access.save(profile(), "original-secret", null).connection
        passwords.writeBarrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { index -> executor.submit<Boolean> {
                try { access.save(original.copy(name = "edit-$index"), "secret-$index", original); true }
                catch (_: IllegalStateException) { false }
            } }.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it })
            val current = settings.find(original.id)!!
            assertEquals(setOf(current.passwordKey), passwords.values.keys)
            assertEquals("secret-${current.name.substringAfter('-')}", access.environment(current).password)
        } finally { executor.shutdownNow() }
    }

    fun testAddressValidationRejectsEmbeddedCredentialsProtocolsAndInvalidPorts() {
        for (address in listOf("ftp://example.com", "http://example.com:99999", "http://user:secret@example.com", "https://example.com?token=secret", "https://example.com/#secret", "http://")) {
            assertFails { NacosConnectionAccess.validated(profile().copy(serverAddr = address)) }
        }
        for (address in listOf("localhost:8848", "HTTP://example.com/nacos", "https://example.com", "http://[::1]:8848/nacos")) {
            assertEquals(address, NacosConnectionAccess.validated(profile().copy(serverAddr = address)).serverAddr)
        }
        val value = NacosConnectionAccess.validated(profile().copy(name = " demo ", namespace = " ", username = " user "))
        assertEquals("demo", value.name)
        assertEquals("", value.namespace)
        assertEquals("user", value.username)
    }

    private fun profile() = ManualNacosConnection(name = "demo", serverAddr = "http://localhost:8848/nacos", username = "demo")

    private fun assertFails(action: () -> Unit) {
        try { action(); fail("Expected validation or snapshot conflict") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }

    internal class MemoryPasswords : NacosPasswordStore {
        val values = ConcurrentHashMap<String, String>()
        @Volatile var failWrite = false
        @Volatile var writeBarrier: CyclicBarrier? = null
        override fun read(key: String) = values[key]
        override fun write(key: String, password: String?) {
            if (password == null) { values.remove(key); return }
            if (failWrite) error("simulated credential store failure")
            values[key] = password
            writeBarrier?.await(5, TimeUnit.SECONDS)
        }
    }
}
