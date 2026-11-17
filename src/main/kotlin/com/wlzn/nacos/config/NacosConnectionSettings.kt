package com.wlzn.nacos.config

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.messages.Topic
import com.wlzn.nacos.model.NacosEnvironment
import com.wlzn.nacos.service.NacosApiClient
import java.util.UUID

data class ManualNacosConnection(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var serverAddr: String = "",
    var namespace: String = "",
    var username: String = "",
    var passwordKey: String = ""
) {
    override fun toString() = name
}

fun interface NacosConnectionsListener {
    fun connectionsChanged()

    companion object {
        val TOPIC = Topic.create("WLZN Nacos connections changed", NacosConnectionsListener::class.java)
    }
}

@Service(Service.Level.APP)
@State(name = "WlznNacosConnections", storages = [Storage(value = "wlzn-nacos-connections.xml", roamingType = RoamingType.DISABLED)])
class NacosConnectionSettings : PersistentStateComponent<NacosConnectionSettings.State> {
    data class State(
        var connections: MutableList<ManualNacosConnection> = mutableListOf(),
        var manualMode: Boolean = false,
        var selectedConnectionId: String = ""
    )

    private var data = State()

    @Synchronized
    override fun getState() = data.copy(connections = data.connections.map { it.copy() }.toMutableList())

    @Synchronized
    override fun loadState(state: State) {
        data = state.copy(connections = state.connections.map { it.copy() }.toMutableList())
    }

    @Synchronized
    fun connections() = data.connections.map { it.copy() }

    @Synchronized
    fun find(id: String) = data.connections.find { it.id == id }?.copy()

    @Synchronized
    fun rememberSelection(manualMode: Boolean, id: String) {
        data.manualMode = manualMode
        data.selectedConnectionId = id
    }

    @Synchronized
    internal fun save(connection: ManualNacosConnection, expected: ManualNacosConnection?) {
        val index = data.connections.indexOfFirst { it.id == connection.id }
        check(data.connections.getOrNull(index) == expected) { "连接已在其他窗口修改，请重新打开后再保存" }
        if (index < 0) data.connections.add(connection.copy()) else data.connections[index] = connection.copy()
    }

    @Synchronized
    internal fun remove(expected: ManualNacosConnection) {
        check(data.connections.find { it.id == expected.id } == expected) { "连接已在其他窗口修改，请重新打开后再移除" }
        data.connections.removeAll { it.id == expected.id }
        if (data.selectedConnectionId == expected.id) data.selectedConnectionId = data.connections.firstOrNull()?.id.orEmpty()
    }

    companion object {
        fun getInstance(): NacosConnectionSettings = service()
    }
}

internal interface NacosPasswordStore {
    fun read(key: String): String?
    fun write(key: String, password: String?)
}

internal class NacosPasswordSafeStore : NacosPasswordStore {
    override fun read(key: String) = PasswordSafe.instance.get(CredentialAttributes("WLZN Nacos Console/$key"))?.getPasswordAsString()
    override fun write(key: String, password: String?) {
        PasswordSafe.instance.set(CredentialAttributes("WLZN Nacos Console/$key"), password?.let { Credentials(null, it) })
    }
}

/** PasswordSafe 仅在后台、无读锁时调用。每次改密使用独立条目，防止多窗口保存时串用凭据。 */
internal class NacosConnectionAccess(
    private val settings: NacosConnectionSettings = NacosConnectionSettings.getInstance(),
    private val passwords: NacosPasswordStore = NacosPasswordSafeStore()
) {
    data class Saved(val connection: ManualNacosConnection, val credentialsCleaned: Boolean)

    fun environment(connection: ManualNacosConnection): NacosEnvironment {
        check(settings.find(connection.id) == connection) { "连接配置已变化，请重新选择连接" }
        val password = passwords.read(connection.passwordKey)
        check(!password.isNullOrEmpty()) { "未找到已保存的密码，请在管理连接中重新填写" }
        check(settings.find(connection.id) == connection) { "连接配置已变化，请重新选择连接" }
        return NacosEnvironment(connection.name, connection.serverAddr, connection.namespace, connection.username, password)
    }

    fun save(candidate: ManualNacosConnection, password: String?, expected: ManualNacosConnection?): Saved {
        val connection = validated(candidate)
        require(expected == null || expected.id == connection.id) { "连接标识不一致" }
        require(password == null || password.isNotEmpty()) { "密码不能为空" }
        require(!password.isNullOrEmpty() || expected?.passwordKey?.isNotEmpty() == true) { "新连接需要填写密码" }
        val newKey = if (password != null) UUID.randomUUID().toString() else expected!!.passwordKey
        val saved = connection.copy(passwordKey = newKey)
        try {
            if (password != null) passwords.write(newKey, password)
            settings.save(saved, expected)
        } catch (e: Exception) {
            if (password != null) cleanup(newKey)
            throw e
        }
        val cleaned = expected == null || expected.passwordKey == newKey || cleanup(expected.passwordKey)
        ApplicationManager.getApplication().messageBus.syncPublisher(NacosConnectionsListener.TOPIC).connectionsChanged()
        return Saved(saved, cleaned)
    }

    fun remove(connection: ManualNacosConnection): Boolean {
        settings.remove(connection)
        val cleaned = cleanup(connection.passwordKey)
        ApplicationManager.getApplication().messageBus.syncPublisher(NacosConnectionsListener.TOPIC).connectionsChanged()
        return cleaned
    }

    private fun cleanup(key: String): Boolean = try {
        if (key.isNotEmpty()) passwords.write(key, null)
        true
    } catch (e: Exception) {
        LOG.warn("Could not clean Nacos credential entry (${e.javaClass.simpleName})")
        false
    }

    companion object {
        private val LOG = Logger.getInstance(NacosConnectionAccess::class.java)

        fun validated(connection: ManualNacosConnection): ManualNacosConnection {
            val value = connection.copy(name = connection.name.trim(), serverAddr = connection.serverAddr.trim(),
                namespace = connection.namespace.trim(), username = connection.username.trim())
            require(value.name.isNotBlank()) { "请填写连接名称" }
            val uri = try { NacosApiClient.addressUri(value.serverAddr) }
            catch (_: IllegalArgumentException) { throw IllegalArgumentException("请填写有效的 HTTP / HTTPS 服务地址、主机和端口") }
            require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "服务地址只能包含主机、端口和上下文路径，请在用户名和密码字段中填写凭据" }
            require(value.username.isNotBlank()) { "请填写用户名" }
            return value
        }
    }
}
