package com.wlzn.nacos.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.wlzn.nacos.model.NacosConfigItem
import com.wlzn.nacos.model.NacosEnvironment
import com.wlzn.nacos.service.NacosApiClient
import com.wlzn.nacos.service.NacosConfigFileParser
import java.awt.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableModel

class NacosConsolePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val apiClient = NacosApiClient()
    private val configParser = NacosConfigFileParser()

    private val configFileComboBox = JComboBox<String>()
    private val envComboBox = JComboBox<NacosEnvironment>()
    private val connectButton = JButton("连接")
    private val refreshButton = JButton("刷新列表")
    private val scanButton = JButton("扫描配置文件")
    private val searchField = JTextField(15)

    private val tableModel = object : DefaultTableModel(arrayOf("DataId", "Group", "类型"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val configTable = JBTable(tableModel)

    private val contentArea = JTextArea()
    private val typeComboBox = JComboBox(arrayOf("yaml", "properties", "json", "text", "xml", "html"))
    private val publishButton = JButton("发布配置")
    private val fetchLatestButton = JButton("获取最新")

    private val statusLabel = JLabel(" 就绪")

    @Volatile
    private var allConfigItems = listOf<NacosConfigItem>()
    private var displayedConfigItems = listOf<NacosConfigItem>()
    @Volatile
    private var currentToken: String = ""
    @Volatile
    private var currentEnv: NacosEnvironment? = null
    private val configFileMap = linkedMapOf<String, VirtualFile>()
    @Volatile
    private var disposed = false

    init {
        setupUI()
        setupListeners()
        ApplicationManager.getApplication().invokeLater({ scanConfigFiles() }, ModalityState.nonModal())
    }

    override fun dispose() {
        disposed = true
    }

    private fun setupUI() {
        val topPanel = JPanel()
        topPanel.layout = BoxLayout(topPanel, BoxLayout.Y_AXIS)

        val row1 = JPanel(FlowLayout(FlowLayout.LEFT, 5, 4))
        row1.add(JLabel("配置文件:"))
        configFileComboBox.preferredSize = Dimension(350, 28)
        row1.add(configFileComboBox)
        row1.add(scanButton)

        val row2 = JPanel(FlowLayout(FlowLayout.LEFT, 5, 4))
        row2.add(JLabel("环境:"))
        envComboBox.preferredSize = Dimension(250, 28)
        row2.add(envComboBox)
        row2.add(connectButton)
        row2.add(refreshButton)
        row2.add(Box.createHorizontalStrut(20))
        row2.add(JLabel("搜索:"))
        row2.add(searchField)

        topPanel.add(row1)
        topPanel.add(row2)

        configTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        configTable.rowHeight = 26
        configTable.setShowGrid(true)
        configTable.gridColor = Color(230, 230, 230)

        val leftPanel = JPanel(BorderLayout())
        leftPanel.add(JBScrollPane(configTable), BorderLayout.CENTER)
        leftPanel.minimumSize = Dimension(300, 200)

        val rightPanel = JPanel(BorderLayout())

        contentArea.font = Font(Font.MONOSPACED, Font.PLAIN, 13)
        contentArea.tabSize = 2
        contentArea.isEditable = true
        contentArea.margin = Insets(5, 5, 5, 5)
        rightPanel.add(JBScrollPane(contentArea), BorderLayout.CENTER)

        val actionPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4))
        actionPanel.add(fetchLatestButton)
        actionPanel.add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = Dimension(1, 24) })
        actionPanel.add(JLabel("格式:"))
        actionPanel.add(typeComboBox)
        actionPanel.add(publishButton)
        rightPanel.add(actionPanel, BorderLayout.SOUTH)

        val splitter = JBSplitter(false, 0.3f)
        splitter.firstComponent = leftPanel
        splitter.secondComponent = rightPanel

        statusLabel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Color(200, 200, 200)),
            BorderFactory.createEmptyBorder(4, 8, 4, 8)
        )
        statusLabel.font = statusLabel.font.deriveFont(12f)

        add(topPanel, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        refreshButton.isEnabled = false
        publishButton.isEnabled = false
        fetchLatestButton.isEnabled = false
    }

    private fun setupListeners() {
        configFileComboBox.addActionListener {
            onConfigFileSelected()
        }

        scanButton.addActionListener {
            scanConfigFiles()
        }

        connectButton.addActionListener {
            connectToNacos()
        }

        refreshButton.addActionListener {
            refreshConfigList()
        }

        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filterConfigList()
            override fun removeUpdate(e: DocumentEvent) = filterConfigList()
            override fun changedUpdate(e: DocumentEvent) = filterConfigList()
        })

        configTable.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                onConfigItemSelected()
            }
        }

        fetchLatestButton.addActionListener {
            fetchLatestConfig()
        }

        publishButton.addActionListener {
            publishConfig()
        }
    }

    private fun scanConfigFiles() {
        val basePath = project.basePath ?: return
        val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return

        val targetNames = setOf(
            "application.yml", "application.yaml",
            "bootstrap.yml", "bootstrap.yaml"
        )
        val skipDirs = setOf(".git", "build", ".gradle", "node_modules", "target", ".idea", "out")

        val previousSelection = configFileComboBox.selectedItem as? String
        configFileMap.clear()

        fun search(dir: VirtualFile) {
            for (child in dir.children) {
                if (child.isDirectory) {
                    if (child.name !in skipDirs) search(child)
                } else if (child.name in targetNames) {
                    child.refresh(false, false)
                    val relativePath = child.path.removePrefix(basePath).removePrefix("/").removePrefix("\\")
                    configFileMap[relativePath] = child
                }
            }
        }

        WriteIntentReadAction.run {
            FileDocumentManager.getInstance().saveAllDocuments()
            search(baseDir)
        }

        configFileComboBox.removeAllItems()
        for (path in configFileMap.keys) {
            configFileComboBox.addItem(path)
        }

        if (configFileMap.isNotEmpty()) {
            val restoreIdx = if (previousSelection != null) {
                (0 until configFileComboBox.itemCount).firstOrNull {
                    configFileComboBox.getItemAt(it) == previousSelection
                } ?: 0
            } else 0
            configFileComboBox.selectedIndex = restoreIdx
            onConfigFileSelected()
        } else {
            setStatus("未找到 Spring Boot 配置文件 (application.yml / bootstrap.yml)")
        }
    }

    private fun onConfigFileSelected() {
        val selectedPath = configFileComboBox.selectedItem as? String ?: return
        val vFile = configFileMap[selectedPath] ?: return

        try {
            val content = WriteIntentReadAction.compute<String> {
                val document = FileDocumentManager.getInstance().getDocument(vFile)
                document?.text ?: String(vFile.contentsToByteArray(), Charsets.UTF_8)
            }
            val environments = configParser.parse(content)

            envComboBox.removeAllItems()
            for (env in environments) {
                envComboBox.addItem(env)
            }

            if (environments.isNotEmpty()) {
                envComboBox.selectedIndex = 0
                setStatus("已解析 ${environments.size} 个环境配置")
            } else {
                setStatus("配置文件中未找到 Nacos 配置")
            }
        } catch (e: Exception) {
            LOG.warn("Failed to parse config file: $selectedPath", e)
            setStatus("解析配置文件失败: ${e.message}")
        }
    }

    private fun connectToNacos() {
        val env = envComboBox.selectedItem as? NacosEnvironment ?: run {
            setStatus("请先选择环境")
            return
        }

        currentEnv = env
        setStatus("正在连接 ${env.serverAddr} ...")
        setUIEnabled(false)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val loginResp = apiClient.login(env.serverAddr, env.username, env.password)
                currentToken = loginResp.accessToken

                val configResp = apiClient.getConfigList(env.serverAddr, env.namespace, currentToken)
                allConfigItems = configResp.pageItems

                invokeLaterIfAlive {
                    updateConfigTable(allConfigItems)
                    refreshButton.isEnabled = true
                    setUIEnabled(true)
                    setStatus("已连接 [${env.profileName}] ${env.serverAddr}，共 ${configResp.totalCount} 个配置")
                }
            } catch (e: Exception) {
                LOG.warn("Failed to connect to Nacos: ${env.serverAddr}", e)
                invokeLaterIfAlive {
                    setUIEnabled(true)
                    setStatus("连接失败: ${e.message}")
                    Messages.showErrorDialog(project, "连接 Nacos 失败:\n${e.message}", "连接错误")
                }
            }
        }
    }

    private fun refreshConfigList() {
        val env = currentEnv ?: return

        setStatus("正在刷新配置列表...")
        setUIEnabled(false)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val loginResp = apiClient.login(env.serverAddr, env.username, env.password)
                currentToken = loginResp.accessToken

                val configResp = apiClient.getConfigList(env.serverAddr, env.namespace, currentToken)
                allConfigItems = configResp.pageItems

                invokeLaterIfAlive {
                    val selectedDataId = getSelectedConfigItem()?.dataId
                    updateConfigTable(allConfigItems)
                    restoreSelection(selectedDataId)
                    setUIEnabled(true)
                    setStatus("已刷新，共 ${configResp.totalCount} 个配置")
                }
            } catch (e: Exception) {
                LOG.warn("Failed to refresh config list", e)
                invokeLaterIfAlive {
                    setUIEnabled(true)
                    setStatus("刷新失败: ${e.message}")
                }
            }
        }
    }

    private fun filterConfigList() {
        val keyword = searchField.text.trim().lowercase()
        val filtered = if (keyword.isEmpty()) {
            allConfigItems
        } else {
            allConfigItems.filter {
                it.dataId.lowercase().contains(keyword) || it.group.lowercase().contains(keyword)
            }
        }
        updateConfigTable(filtered)
    }

    private fun updateConfigTable(items: List<NacosConfigItem>) {
        displayedConfigItems = items
        tableModel.setRowCount(0)
        for (item in items) {
            tableModel.addRow(arrayOf(item.dataId, item.group, item.type))
        }
    }

    private fun onConfigItemSelected() {
        val item = getSelectedConfigItem() ?: run {
            contentArea.text = ""
            publishButton.isEnabled = false
            fetchLatestButton.isEnabled = false
            return
        }

        contentArea.text = item.content
        contentArea.caretPosition = 0

        val typeIdx = (0 until typeComboBox.itemCount).firstOrNull {
            typeComboBox.getItemAt(it) == item.type
        } ?: -1
        if (typeIdx >= 0) typeComboBox.selectedIndex = typeIdx

        publishButton.isEnabled = true
        fetchLatestButton.isEnabled = true
        setStatus("已选中: ${item.dataId} [${item.group}]")
    }

    private fun fetchLatestConfig() {
        val item = getSelectedConfigItem() ?: return
        val env = currentEnv ?: return

        setStatus("正在获取 ${item.dataId} 的最新配置...")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val content = apiClient.getConfig(env.serverAddr, item.dataId, item.group, env.namespace, currentToken)
                invokeLaterIfAlive {
                    contentArea.text = content
                    contentArea.caretPosition = 0
                    setStatus("已获取 ${item.dataId} 的最新配置")
                }
            } catch (e: Exception) {
                LOG.warn("Failed to fetch config: ${item.dataId}", e)
                invokeLaterIfAlive {
                    setStatus("获取失败: ${e.message}")
                }
            }
        }
    }

    private fun publishConfig() {
        val item = getSelectedConfigItem() ?: return
        val env = currentEnv ?: return
        val content = contentArea.text

        if (content.isBlank()) {
            Messages.showWarningDialog(project, "配置内容不能为空", "发布提示")
            return
        }

        val confirm = Messages.showYesNoDialog(
            project,
            "确认将以下配置发布到 [${env.profileName}] 环境？\n\n" +
                "DataId: ${item.dataId}\n" +
                "Group: ${item.group}\n" +
                "Server: ${env.serverAddr}\n" +
                "Namespace: ${env.namespace}",
            "发布确认",
            Messages.getQuestionIcon()
        )

        if (confirm != Messages.YES) return

        val type = typeComboBox.selectedItem as? String ?: "yaml"
        setStatus("正在发布 ${item.dataId} ...")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val success = apiClient.publishConfig(
                    env.serverAddr, item.dataId, item.group, env.namespace, content, type, currentToken
                )
                invokeLaterIfAlive {
                    if (success) {
                        setStatus("发布成功: ${item.dataId}")
                        notify("配置 ${item.dataId} 发布成功", NotificationType.INFORMATION)
                        refreshConfigList()
                    } else {
                        setStatus("发布失败: ${item.dataId}")
                        notify("配置 ${item.dataId} 发布失败，请检查权限和网络", NotificationType.ERROR)
                    }
                }
            } catch (e: Exception) {
                LOG.warn("Failed to publish config: ${item.dataId}", e)
                invokeLaterIfAlive {
                    setStatus("发布异常: ${e.message}")
                    notify("发布异常: ${e.message}", NotificationType.ERROR)
                }
            }
        }
    }

    private fun getSelectedConfigItem(): NacosConfigItem? {
        val row = configTable.selectedRow
        if (row < 0 || row >= displayedConfigItems.size) return null
        return displayedConfigItems[row]
    }

    private fun restoreSelection(dataId: String?) {
        if (dataId == null) return
        val idx = displayedConfigItems.indexOfFirst { it.dataId == dataId }
        if (idx >= 0) {
            configTable.setRowSelectionInterval(idx, idx)
        }
    }

    private fun setStatus(text: String) {
        statusLabel.text = " $text"
    }

    private fun setUIEnabled(enabled: Boolean) {
        connectButton.isEnabled = enabled
        refreshButton.isEnabled = enabled && currentEnv != null
        configFileComboBox.isEnabled = enabled
        envComboBox.isEnabled = enabled
        scanButton.isEnabled = enabled
    }

    private fun invokeLaterIfAlive(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            if (!disposed && !project.isDisposed) action()
        }, ModalityState.defaultModalityState())
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Nacos Console")
            .createNotification(content, type)
            .notify(project)
    }

    companion object {
        private val LOG = Logger.getInstance(NacosConsolePanel::class.java)
    }
}
