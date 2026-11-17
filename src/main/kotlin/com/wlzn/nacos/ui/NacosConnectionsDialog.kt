package com.wlzn.nacos.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.wlzn.nacos.config.ManualNacosConnection
import com.wlzn.nacos.config.NacosConnectionAccess
import com.wlzn.nacos.config.NacosConnectionSettings
import com.wlzn.nacos.service.NacosApiClient
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ActionEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal class NacosConnectionsDialog(
    private val project: Project,
    selectedId: String,
    private val settings: NacosConnectionSettings = NacosConnectionSettings.getInstance(),
    private val access: NacosConnectionAccess = NacosConnectionAccess(settings)
) : DialogWrapper(project) {
    private val listModel = DefaultListModel<ManualNacosConnection>()
    private val connections = JBList(listModel)
    private val connectionName = JTextField(30).apply { name = "connectionName" }
    private val address = JTextField(35).apply { name = "serverAddress" }
    private val namespace = JTextField(30).apply { name = "namespaceId" }
    private val username = JTextField(25).apply { name = "username" }
    private val password = JPasswordField(25).apply { name = "password" }
    private val status = JLabel(" ").apply { putClientProperty("html.disable", true) }
    private val addButton = JButton("添加连接", AllIcons.General.Add)
    private val removeButton = JButton("移除此连接")
    private var existing: ManualNacosConnection? = null
    private var draftId = ""
    private var updating = false
    private var busy = false
    private var formGeneration = 0
    var savedConnection: ManualNacosConnection? = null
        private set

    private val testAction = object : AbstractAction("测试连接") {
        override fun actionPerformed(event: ActionEvent?) { testConnection() }
    }

    init {
        title = "管理手动连接"
        setOKButtonText("保存并连接")
        init()
        setSize(JBUI.scale(780), JBUI.scale(540))
        val renderer = DefaultListCellRenderer()
        connections.cellRenderer = ListCellRenderer { list, value, index, selected, focus ->
            renderer.getListCellRendererComponent(list, value.name, index, selected, focus).apply {
                (this as JLabel).putClientProperty("html.disable", true)
                icon = AllIcons.Nodes.Services
                border = JBUI.Borders.empty(10, 8)
                toolTipText = "${value.serverAddr} · ${value.namespace.ifBlank { "public" }}"
            }
        }
        connections.selectionMode = ListSelectionModel.SINGLE_SELECTION
        connections.addListSelectionListener {
            if (!updating && !it.valueIsAdjusting && !busy) {
                if (confirmDiscardChanges()) loadForm(connections.selectedValue)
                else {
                    updating = true
                    connections.selectedIndex = (0 until listModel.size()).firstOrNull { index -> listModel[index].id == existing?.id } ?: -1
                    updating = false
                }
            }
        }
        addButton.addActionListener { if (confirmDiscardChanges()) { updating = true; connections.clearSelection(); updating = false; loadForm(null) } }
        removeButton.addActionListener { removeConnection() }
        val listener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = changed()
            override fun removeUpdate(e: DocumentEvent) = changed()
            override fun changedUpdate(e: DocumentEvent) = changed()
            private fun changed() { if (!updating) { formGeneration++; status.text = " " } }
        }
        fields().forEach { it.document.addDocumentListener(listener) }
        reload(selectedId)
    }

    override fun continuousValidation() = true
    override fun createActions(): Array<Action> = arrayOf(cancelAction, okAction)
    override fun createLeftSideActions(): Array<Action> = arrayOf(testAction)

    override fun createCenterPanel(): JComponent {
        val form = JPanel(GridBagLayout()).apply { border = JBUI.Borders.empty(12, 20) }
        var row = 0
        fun field(label: String, component: JComponent, comment: String? = null) {
            form.add(JLabel(label), GridBagConstraints().apply { gridx = 0; gridy = row++; anchor = GridBagConstraints.WEST; insets = JBUI.insets(8, 0, 4, 0) })
            form.add(component, GridBagConstraints().apply { gridx = 0; gridy = row++; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0 })
            if (comment != null) form.add(note(comment), GridBagConstraints().apply { gridx = 0; gridy = row++; anchor = GridBagConstraints.WEST; insets = JBUI.insetsTop(4) })
        }
        field("连接名称", connectionName)
        field("Nacos 服务地址", address, "填写可访问的服务地址，可包含 /nacos 路径")
        field("Namespace ID", namespace, "填写命名空间 ID，留空使用 public")
        val credentials = JPanel(GridBagLayout())
        listOf("用户名" to username, "密码" to password).forEachIndexed { column, (label, component) ->
            credentials.add(JLabel(label), GridBagConstraints().apply { gridx = column; gridy = 0; anchor = GridBagConstraints.WEST; insets = JBUI.insets(8, if (column == 0) 0 else 10, 4, 0) })
            credentials.add(component, GridBagConstraints().apply { gridx = column; gridy = 1; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; insets = JBUI.insetsLeft(if (column == 0) 0 else 10) })
        }
        form.add(credentials, GridBagConstraints().apply { gridx = 0; gridy = row++; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0 })
        form.add(note("密码存入 IDEA 凭据库，编辑时留空保留原值"), GridBagConstraints().apply { gridx = 0; gridy = row++; anchor = GridBagConstraints.WEST; insets = JBUI.insetsTop(6) })
        form.add(removeButton, GridBagConstraints().apply { gridx = 0; gridy = row++; anchor = GridBagConstraints.EAST; insets = JBUI.insetsTop(8) })
        form.add(status, GridBagConstraints().apply { gridx = 0; gridy = row; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; weighty = 1.0; anchor = GridBagConstraints.SOUTHWEST; insets = JBUI.insetsTop(10) })
        val sidebar = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(12, 8)
            add(note("已保存的连接"), BorderLayout.NORTH)
            add(JBScrollPane(connections), BorderLayout.CENTER)
            add(JPanel(BorderLayout(0, JBUI.scale(12))).apply {
                add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(addButton) }, BorderLayout.NORTH)
                add(note("在所有项目中可用"), BorderLayout.SOUTH)
            }, BorderLayout.SOUTH)
        }
        return JBSplitter(false, 0.25f).apply { firstComponent = sidebar; secondComponent = form }
    }

    private fun fields(): List<JTextField> = listOf(connectionName, address, namespace, username, password)

    private fun profile() = ManualNacosConnection(draftId, connectionName.text, address.text, namespace.text, username.text)

    private fun enteredPassword(): String? = String(password.password).takeIf { it.isNotEmpty() }

    private fun confirmDiscardChanges(): Boolean {
        val changed = if (existing == null) fields().any { it.text.isNotEmpty() }
            else profile() != existing!!.copy(passwordKey = "") || enteredPassword() != null
        return !changed || Messages.showYesNoDialog(project, "当前连接有未保存的修改，是否丢弃？", "未保存的连接", Messages.getWarningIcon()) == Messages.YES
    }

    override fun doValidate(): ValidationInfo? {
        if (connectionName.text.isBlank()) return ValidationInfo("请填写连接名称", connectionName)
        try { NacosConnectionAccess.validated(profile()) }
        catch (e: IllegalArgumentException) { return ValidationInfo(e.message ?: "服务地址无效", if (e.message == "请填写用户名") username else address) }
        if (existing == null && enteredPassword() == null) return ValidationInfo("新连接需要填写密码", password)
        return null
    }

    private fun loadForm(value: ManualNacosConnection?) {
        existing = value?.copy()
        draftId = value?.id ?: java.util.UUID.randomUUID().toString()
        updating = true
        connectionName.text = value?.name.orEmpty()
        address.text = value?.serverAddr.orEmpty()
        namespace.text = value?.namespace.orEmpty()
        username.text = value?.username.orEmpty()
        password.text = ""
        updating = false
        formGeneration++
        status.text = " "
        removeButton.isVisible = value != null
        connectionName.requestFocusInWindow()
    }

    private fun reload(selectedId: String) {
        updating = true
        listModel.clear()
        settings.connections().forEach(listModel::addElement)
        connections.selectedIndex = (0 until listModel.size()).firstOrNull { listModel[it].id == selectedId } ?: if (listModel.isEmpty) -1 else 0
        updating = false
        loadForm(connections.selectedValue)
    }

    private fun testConnection() {
        if (busy) return
        val validation = doValidate()
        if (validation != null) { status.foreground = JBColor.RED; status.text = validation.message; validation.component?.requestFocusInWindow(); return }
        val candidate = NacosConnectionAccess.validated(profile())
        val expected = existing?.copy()
        val entered = enteredPassword()
        val generation = formGeneration
        setBusy(true, "正在验证连接…")
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val secret = entered ?: access.environment(checkNotNull(expected)).password
                val api = NacosApiClient()
                val login = api.login(candidate.serverAddr, candidate.username, secret)
                api.getConfigList(candidate.serverAddr, candidate.namespace, login.accessToken, pageSize = 1)
                later { if (generation == formGeneration) setBusy(false, "验证成功 · 可以读取配置列表") }
            } catch (e: Exception) { failed("验证连接", e) }
        }
    }

    override fun doOKAction() {
        if (busy) return
        val validation = doValidate()
        if (validation != null) {
            status.foreground = JBColor.RED
            status.text = validation.message
            validation.component?.requestFocusInWindow()
            return
        }
        val candidate = NacosConnectionAccess.validated(profile())
        val expected = existing?.copy()
        val entered = enteredPassword()
        setBusy(true, "正在安全保存连接…")
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = access.save(candidate, entered, expected)
                later {
                    savedConnection = result.connection
                    if (!result.credentialsCleaned) Messages.showWarningDialog(project, "连接已保存，但旧密码条目清理失败，请检查 IDEA 凭据库。", "凭据清理提示")
                    close(OK_EXIT_CODE)
                }
            } catch (e: Exception) { failed("保存连接", e) }
        }
    }

    private fun removeConnection() {
        val expected = existing?.copy() ?: return
        if (busy || Messages.showYesNoDialog(project, "移除连接“${expected.name}”？", "移除连接", Messages.getQuestionIcon()) != Messages.YES) return
        setBusy(true, "正在移除连接…")
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val cleaned = access.remove(expected)
                later { reload(""); setBusy(false, if (cleaned) "连接已移除" else "连接已移除，但密码条目清理失败，请检查 IDEA 凭据库") }
            } catch (e: Exception) { failed("移除连接", e) }
        }
    }

    override fun doCancelAction() { if (!busy) super.doCancelAction() }

    private fun setBusy(value: Boolean, message: String) {
        busy = value
        fields().forEach { it.isEnabled = !value }
        connections.isEnabled = !value
        addButton.isEnabled = !value
        removeButton.isEnabled = !value
        testAction.isEnabled = !value
        isOKActionEnabled = !value
        cancelAction.isEnabled = !value
        status.foreground = UIUtil.getContextHelpForeground()
        status.text = message
    }

    private fun failed(operation: String, error: Exception) {
        LOG.warn("Nacos manual connection operation failed: $operation (${error.javaClass.simpleName})")
        val message = error.message?.takeIf { it.startsWith("连接已在其他窗口修改") || it.startsWith("连接配置已变化") || it.startsWith("未找到已保存的密码") }
            ?: "$operation 失败，请检查地址、Namespace、凭据及 IDEA 凭据库（${error.javaClass.simpleName}）"
        later { setBusy(false, message); status.foreground = JBColor.RED }
    }

    private fun later(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ if (!isDisposed && !project.isDisposed) action() }, ModalityState.any())
    }

    companion object {
        private val LOG = Logger.getInstance(NacosConnectionsDialog::class.java)
        private fun note(text: String) = JLabel(text).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
            putClientProperty("html.disable", true)
        }
    }
}
