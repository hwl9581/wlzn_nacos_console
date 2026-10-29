package com.wlzn.nacos.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.ui.JBSplitter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.wlzn.nacos.model.NacosConfigItem
import com.wlzn.nacos.model.NacosEnvironment
import com.wlzn.nacos.service.NacosApiClient
import com.wlzn.nacos.service.NacosConfigFileParser
import java.awt.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class NacosConsolePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val apiClient = NacosApiClient()
    private val configParser = NacosConfigFileParser()

    private val configFileComboBox = JComboBox<String>()
    private val envComboBox = JComboBox<NacosEnvironment>()
    private val connectButton = JButton("连接")
    private val refreshButton = JButton("刷新列表")
    private val scanButton = JButton("扫描配置文件")
    private val searchField = SearchTextField(false)
    private val listModel = DefaultListModel<NacosConfigItem>()
    private val configList = JBList(listModel)
    private val contentArea = NacosConfigEditor(project)
    private val typeComboBox = JComboBox(arrayOf("yaml", "properties", "json", "text", "xml", "html"))
    private val publishButton = object : JButton("发布配置") {
        // 只使用原生主按钮外观，不给工具窗口注册回车发布快捷键。
        override fun isDefaultButton() = true
    }
    private val fetchLatestButton = JButton("获取最新")
    private val historyButton = JButton("历史版本")

    private val statusLabel = JLabel(" 就绪")
    private val connectionLabel = smallLabel("未连接")
    private val countLabel = smallLabel("0")
    private val configTitle = JLabel("未选择配置")
    private val configMetadata = smallLabel("选择配置后显示 Data ID / Group / Namespace")
    private val editorInfo = smallLabel("UTF-8 · 2 空格")
    private val highlightingLabel = smallLabel("语法高亮跟随 IDEA 编辑器配色")
    private var updatingList = false

    @Volatile
    private var allConfigItems = listOf<NacosConfigItem>()
    private var contentRequestId = 0
    private var configParseId = 0
    @Volatile
    private var connectionId = 0
    private var scanning = false
    private var busy = false
    private var contentLoaded = false
    @Volatile
    private var currentToken: String = ""
    @Volatile
    private var currentEnv: NacosEnvironment? = null
    private val configFileMap = linkedMapOf<String, VirtualFile>()
    @Volatile
    private var disposed = false

    init {
        Disposer.register(this, contentArea)
        setupUI()
        setupListeners()
        ApplicationManager.getApplication().invokeLater({ scanConfigFiles() }, ModalityState.nonModal())
    }

    override fun dispose() {
        disposed = true
    }

    private fun setupUI() {
        val topPanel = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createCompoundBorder(JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0), JBUI.Borders.empty(8, 12))
        }
        configFileComboBox.preferredSize = JBUI.size(300, 30)
        configFileComboBox.minimumSize = JBUI.size(100, 30)
        configFileComboBox.toolTipText = "用于解析 Nacos 环境的 Spring Boot 配置文件"
        envComboBox.preferredSize = JBUI.size(175, 30)
        envComboBox.minimumSize = JBUI.size(110, 30)
        envComboBox.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): Component {
                super.getListCellRendererComponent(list, value, index, selected, focus)
                putClientProperty("html.disable", true)
                text = (value as? NacosEnvironment)?.profileName ?: "选择环境"
                toolTipText = (value as? NacosEnvironment)?.serverAddr
                return this
            }
        }
        scanButton.text = ""
        scanButton.icon = AllIcons.Actions.Find
        scanButton.toolTipText = "扫描配置文件"
        scanButton.accessibleContext.accessibleName = "扫描配置文件"
        scanButton.preferredSize = JBUI.size(28, 28)
        refreshButton.text = ""
        refreshButton.icon = AllIcons.Actions.Refresh
        refreshButton.toolTipText = "刷新配置列表"
        refreshButton.accessibleContext.accessibleName = "刷新配置列表"
        refreshButton.preferredSize = JBUI.size(28, 28)
        fetchLatestButton.icon = AllIcons.Actions.Refresh
        historyButton.icon = AllIcons.Vcs.History
        publishButton.icon = AllIcons.Actions.Upload
        val controls = listOf(smallLabel("配置源"), configFileComboBox, scanButton, smallLabel("环境"), envComboBox, connectButton, connectionLabel)
        controls.forEachIndexed { index, component ->
            topPanel.add(component, GridBagConstraints().apply {
                gridx = index; gridy = 0
                weightx = if (component === connectionLabel) 1.0 else 0.0
                fill = GridBagConstraints.NONE
                anchor = if (component === connectionLabel) GridBagConstraints.EAST else GridBagConstraints.WEST
                insets = JBUI.insets(0, if (index == 0) 0 else 8, 0, 0)
            })
        }

        configList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        configList.emptyText.text = "连接 Nacos 后显示配置"
        configList.cellRenderer = ListCellRenderer { list, item, _, selected, _ ->
            val foreground = if (selected) list.selectionForeground else list.foreground
            JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                background = if (selected) list.selectionBackground else list.background
                border = JBUI.Borders.empty(10, 12)
                add(JLabel(AllIcons.FileTypes.Config), BorderLayout.WEST)
                add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                    isOpaque = false
                    add(JLabel(item.dataId).apply { this.foreground = foreground; putClientProperty("html.disable", true) }, BorderLayout.NORTH)
                    add(smallLabel(item.group).apply { if (selected) this.foreground = foreground }, BorderLayout.SOUTH)
                }, BorderLayout.CENTER)
                add(smallLabel(item.type.ifBlank { "text" }).apply { if (selected) this.foreground = foreground }, BorderLayout.EAST)
                toolTipText = "Data ID: ${item.dataId} · ${item.group}"
                size = Dimension(list.width.coerceAtLeast(1), preferredSize.height)
                doLayout()
                components.filterIsInstance<JPanel>().forEach { it.doLayout() }
            }
        }
        val listHeader = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 12)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false; add(JLabel("配置列表")); add(countLabel)
            }, BorderLayout.WEST)
            add(refreshButton, BorderLayout.EAST)
        }
        searchField.textEditor.emptyText.text = "搜索 Data ID / Group"
        searchField.textEditor.accessibleContext.accessibleName = "搜索 Data ID 或 Group"
        val searchPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 12, 10, 12)
            add(searchField)
        }
        val leftPanel = JPanel(BorderLayout()).apply {
            add(JPanel(BorderLayout()).apply { add(listHeader, BorderLayout.NORTH); add(searchPanel, BorderLayout.CENTER) }, BorderLayout.NORTH)
            add(JBScrollPane(configList).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
            minimumSize = JBUI.size(200, 180)
        }

        configTitle.putClientProperty("html.disable", true)
        configTitle.icon = AllIcons.FileTypes.Config
        val editorHeader = JPanel(BorderLayout(12, 0)).apply {
            border = BorderFactory.createCompoundBorder(JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0), JBUI.Borders.empty(10, 14))
            add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                isOpaque = false; add(configTitle, BorderLayout.NORTH); add(configMetadata, BorderLayout.SOUTH)
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false; add(historyButton); add(fetchLatestButton); add(publishButton)
            }, BorderLayout.EAST)
        }
        val formatPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false; add(smallLabel("格式")); add(typeComboBox)
        }
        val editorFooter = JPanel(BorderLayout(8, 0)).apply {
            border = BorderFactory.createCompoundBorder(JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0), JBUI.Borders.empty(6, 12))
            add(highlightingLabel, BorderLayout.CENTER); add(formatPanel, BorderLayout.EAST)
        }
        val rightPanel = JPanel(BorderLayout()).apply {
            add(editorHeader, BorderLayout.NORTH); add(contentArea, BorderLayout.CENTER); add(editorFooter, BorderLayout.SOUTH)
            minimumSize = JBUI.size(320, 180)
        }

        val splitter = JBSplitter(false, 0.25f)
        splitter.firstComponent = leftPanel
        splitter.secondComponent = rightPanel
        val statusPanel = JPanel(BorderLayout(12, 0)).apply {
            border = BorderFactory.createCompoundBorder(JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0), JBUI.Borders.empty(5, 12))
            add(statusLabel, BorderLayout.CENTER); add(editorInfo, BorderLayout.EAST)
        }
        statusLabel.foreground = UIUtil.getContextHelpForeground()
        statusLabel.font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
        statusLabel.putClientProperty("html.disable", true)

        add(topPanel, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)
        add(statusPanel, BorderLayout.SOUTH)

        refreshButton.isEnabled = false
        publishButton.isEnabled = false
        fetchLatestButton.isEnabled = false
        typeComboBox.isEnabled = false
        historyButton.isEnabled = false
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

        searchField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filterConfigList()
            override fun removeUpdate(e: DocumentEvent) = filterConfigList()
            override fun changedUpdate(e: DocumentEvent) = filterConfigList()
        })

        configList.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting && !updatingList) {
                onConfigItemSelected()
            }
        }

        fetchLatestButton.addActionListener {
            fetchLatestConfig()
        }

        publishButton.addActionListener {
            publishConfig()
        }
        historyButton.addActionListener {
            val item = getSelectedConfigItem()
            val environment = currentEnv
            if (!busy && contentLoaded && item != null && environment != null) {
                NacosHistoryDialog(project, apiClient, environment, item, currentToken, contentArea.text, contentArea.format).show()
            }
        }
        typeComboBox.addActionListener { updateEditorFormat() }
        contentArea.onTextChanged = { lineCount -> editorInfo.text = "UTF-8 · $lineCount 行 · 2 空格" }
    }

    private fun scanConfigFiles() {
        if (disposed || project.isDisposed || scanning) return
        val basePath = project.basePath ?: return
        scanning = true
        scanButton.isEnabled = false
        setStatus("正在扫描配置文件...")
        val targetNames = setOf(
            "application.yml", "application.yaml",
            "bootstrap.yml", "bootstrap.yaml"
        )
        val skipDirs = setOf(".git", "build", ".gradle", "node_modules", "target", ".idea", "out")

        val previousSelection = configFileComboBox.selectedItem as? String
        ApplicationManager.getApplication().executeOnPooledThread {
            val baseDir = LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath)
            if (baseDir == null) {
                invokeLaterIfAlive {
                    scanning = false
                    scanButton.isEnabled = !busy
                    setStatus("项目目录不存在")
                }
                return@executeOnPooledThread
            }
            // VFS 刷新不能在读动作中同步执行，刷新完成后再可取消地遍历。
            baseDir.refresh(true, true) {
                ReadAction.nonBlocking<Map<String, VirtualFile>> {
                    val files = linkedMapOf<String, VirtualFile>()
                    fun search(dir: VirtualFile) {
                        ProgressManager.checkCanceled()
                        for (child in dir.children) {
                            if (child.isDirectory) {
                                if (child.name !in skipDirs && !child.`is`(VFileProperty.SYMLINK)) search(child)
                            } else if (child.name in targetNames) {
                                files[child.path.removePrefix(basePath).removePrefix("/")] = child
                            }
                        }
                    }
                    search(baseDir)
                    files
                }.expireWith(this)
                    .finishOnUiThread(ModalityState.nonModal()) { files ->
                        if (disposed || project.isDisposed) return@finishOnUiThread
                        configFileMap.clear()
                        configFileMap.putAll(files)
                        configFileComboBox.model = DefaultComboBoxModel(files.keys.toTypedArray())
                        scanning = false
                        scanButton.isEnabled = !busy
                        if (files.isNotEmpty()) {
                            configFileComboBox.selectedItem = previousSelection?.takeIf { it in files } ?: files.keys.first()
                            onConfigFileSelected()
                        } else {
                            envComboBox.removeAllItems()
                            setStatus("未找到 Spring Boot 配置文件 (application.yml / bootstrap.yml)")
                        }
                    }.submit(AppExecutorUtil.getAppExecutorService())
            }
        }
    }

    private fun onConfigFileSelected() {
        val selectedPath = configFileComboBox.selectedItem as? String ?: return
        val vFile = configFileMap[selectedPath] ?: return
        val parseId = ++configParseId
        envComboBox.removeAllItems()
        connectButton.isEnabled = false

        ReadAction.nonBlocking<Result<List<NacosEnvironment>>> {
            try {
                val document = FileDocumentManager.getInstance().getDocument(vFile)
                Result.success(configParser.parse(document?.text ?: String(vFile.contentsToByteArray(), Charsets.UTF_8)))
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }.expireWith(this)
            .finishOnUiThread(ModalityState.nonModal()) { result ->
                if (disposed || project.isDisposed || parseId != configParseId || configFileComboBox.selectedItem != selectedPath) return@finishOnUiThread
                envComboBox.removeAllItems()
                result.fold(onSuccess = { environments ->
                    environments.forEach { envComboBox.addItem(it) }
                    connectButton.isEnabled = !busy && environments.isNotEmpty()
                    setStatus(if (environments.isNotEmpty()) "已解析 ${environments.size} 个环境配置" else "配置文件中未找到 Nacos 配置")
                }, onFailure = { e ->
                    LOG.warn("Failed to parse config file: $selectedPath", e)
                    setStatus("解析配置文件失败: ${e.message}")
                })
            }.submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun connectToNacos() {
        val env = envComboBox.selectedItem as? NacosEnvironment ?: run {
            setStatus("请先选择环境")
            return
        }

        connectionId++
        val requestConnectionId = connectionId
        currentEnv = null
        currentToken = ""
        allConfigItems = emptyList()
        updateConfigList(emptyList())
        connectionLabel.text = "正在连接…"
        connectionLabel.icon = null
        setStatus("正在连接 ${env.serverAddr} ...")
        setUIEnabled(false)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val loginResp = apiClient.login(env.serverAddr, env.username, env.password)
                val configResp = apiClient.getConfigList(env.serverAddr, env.namespace, loginResp.accessToken)

                invokeLaterIfAlive {
                    if (connectionId != requestConnectionId) return@invokeLaterIfAlive
                    currentEnv = env
                    currentToken = loginResp.accessToken
                    allConfigItems = configResp.pageItems
                    updateConfigList(allConfigItems)
                    connectionLabel.text = "${env.profileName} 已连接"
                    connectionLabel.icon = AllIcons.General.InspectionsOK
                    connectionLabel.toolTipText = env.serverAddr
                    refreshButton.isEnabled = true
                    setUIEnabled(true)
                    setStatus("已连接 [${env.profileName}] ${env.serverAddr}，共 ${configResp.totalCount} 个配置")
                }
            } catch (e: Exception) {
                LOG.warn("Failed to connect to Nacos: ${env.serverAddr}", e)
                invokeLaterIfAlive {
                    setUIEnabled(true)
                    connectionLabel.text = "连接失败"
                    setStatus("连接失败: ${e.message}")
                    Messages.showErrorDialog(project, "连接 Nacos 失败:\n${e.message}", "连接错误")
                }
            }
        }
    }

    private fun refreshConfigList() {
        val env = currentEnv ?: return
        val requestConnectionId = connectionId

        setStatus("正在刷新配置列表...")
        setUIEnabled(false)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val loginResp = apiClient.login(env.serverAddr, env.username, env.password)
                val configResp = apiClient.getConfigList(env.serverAddr, env.namespace, loginResp.accessToken)

                invokeLaterIfAlive {
                    if (connectionId != requestConnectionId) return@invokeLaterIfAlive
                    currentToken = loginResp.accessToken
                    allConfigItems = configResp.pageItems
                    val keyword = searchField.text.trim().lowercase()
                    updateConfigList(allConfigItems.filter { keyword.isEmpty() || it.dataId.lowercase().contains(keyword) || it.group.lowercase().contains(keyword) })
                    setUIEnabled(true)
                    setStatus("已刷新，共 ${configResp.totalCount} 个配置")
                    if (getSelectedConfigItem() != null) onConfigItemSelected()
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
        updateConfigList(filtered)
    }

    private fun updateConfigList(items: List<NacosConfigItem>) {
        val selected = getSelectedConfigItem()
        updatingList = true
        try {
            listModel.clear()
            items.forEach { listModel.addElement(it) }
            configList.selectedIndex = items.indexOfFirst { selected != null && it.dataId == selected.dataId && it.group == selected.group }
        } finally {
            updatingList = false
        }
        countLabel.text = items.size.toString()
        configList.emptyText.text = if (currentEnv == null) "连接 Nacos 后显示配置" else "没有匹配的配置"
        // 搜索仍包含当前行时保留编辑草稿，不能因列表重建而重新载入正文。
        if (getSelectedConfigItem() == null) onConfigItemSelected()
    }

    private fun onConfigItemSelected() {
        contentRequestId++
        contentLoaded = false
        contentArea.isEditable = false
        historyButton.isEnabled = false
        val item = getSelectedConfigItem() ?: run {
            contentArea.text = ""
            configTitle.text = "未选择配置"
            configMetadata.text = "选择配置后显示 Data ID / Group / Namespace"
            typeComboBox.isEnabled = false
            publishButton.isEnabled = false
            fetchLatestButton.isEnabled = false
            return
        }

        configTitle.text = item.dataId
        configTitle.toolTipText = "Data ID: ${item.dataId}"
        configMetadata.text = "${item.group}  /  namespace: ${currentEnv?.namespace?.ifBlank { "public" } ?: "public"}"
        configMetadata.toolTipText = configMetadata.text
        contentArea.text = item.content
        typeComboBox.selectedItem = NacosConfigEditor.normalizeFormat(item.type.ifBlank { item.dataId.substringAfterLast('.', "text") })
        updateEditorFormat()
        typeComboBox.isEnabled = !busy

        publishButton.isEnabled = false
        fetchLatestButton.isEnabled = !busy
        setStatus("已选中: ${item.dataId} [${item.group}]")
        fetchLatestConfig()
    }

    private fun fetchLatestConfig() {
        val item = getSelectedConfigItem() ?: return
        val env = currentEnv ?: return
        val requestId = ++contentRequestId
        val requestConnectionId = connectionId
        val token = currentToken
        contentLoaded = false
        contentArea.isEditable = false
        publishButton.isEnabled = false
        fetchLatestButton.isEnabled = false
        historyButton.isEnabled = false

        setStatus("正在获取 ${item.dataId} 的最新配置...")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val content = apiClient.getConfig(env.serverAddr, item.dataId, item.group, env.namespace, token)
                invokeLaterIfAlive {
                    if (requestId != contentRequestId || requestConnectionId != connectionId) return@invokeLaterIfAlive
                    contentArea.text = content
                    contentLoaded = true
                    contentArea.isEditable = !busy
                    publishButton.isEnabled = !busy
                    fetchLatestButton.isEnabled = !busy
                    historyButton.isEnabled = !busy
                    setStatus("已获取 ${item.dataId} 的最新配置")
                }
            } catch (e: Exception) {
                LOG.warn("Failed to fetch config: ${item.dataId}", e)
                invokeLaterIfAlive {
                    if (requestId != contentRequestId || requestConnectionId != connectionId) return@invokeLaterIfAlive
                    fetchLatestButton.isEnabled = !busy
                    setStatus("获取失败: ${e.message}")
                }
            }
        }
    }

    private fun publishConfig() {
        if (busy || !contentLoaded) return
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
        val requestConnectionId = connectionId
        setUIEnabled(false)
        setStatus("正在重新认证并发布 ${item.dataId} ...")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // 发布前获取新 Token，避免长时间打开工具窗口后复用过期登录态。
                // 登录或只读路由探测失败就停止，配置正文仍只 POST 一次，不重试失败的写入。
                val loginResp = apiClient.login(env.serverAddr, env.username, env.password)
                if (disposed || project.isDisposed || requestConnectionId != connectionId) return@executeOnPooledThread
                invokeLaterIfAlive {
                    if (requestConnectionId == connectionId) currentToken = loginResp.accessToken
                }
                val success = apiClient.publishConfig(
                    env.serverAddr, item.dataId, item.group, env.namespace, content, type, loginResp.accessToken
                )
                invokeLaterIfAlive {
                    if (requestConnectionId != connectionId) return@invokeLaterIfAlive
                    setUIEnabled(true)
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
                    if (requestConnectionId != connectionId) return@invokeLaterIfAlive
                    setUIEnabled(true)
                    setStatus("发布异常: ${e.message}")
                    notify("发布异常: ${e.message}", NotificationType.ERROR)
                }
            }
        }
    }

    private fun getSelectedConfigItem(): NacosConfigItem? = configList.selectedValue

    private fun updateEditorFormat() {
        contentArea.setFormat(typeComboBox.selectedItem as? String ?: "text")
        highlightingLabel.text = if (contentArea.highlightingAvailable) "注释、键名和值按 IDEA 配色区分" else "当前格式的语言支持未启用，按纯文本显示"
    }

    private fun setStatus(text: String) {
        statusLabel.text = " $text"
    }

    private fun setUIEnabled(enabled: Boolean) {
        busy = !enabled
        connectButton.isEnabled = enabled && envComboBox.itemCount > 0
        refreshButton.isEnabled = enabled && currentEnv != null
        configFileComboBox.isEnabled = enabled
        envComboBox.isEnabled = enabled
        scanButton.isEnabled = enabled && !scanning
        configList.isEnabled = enabled
        searchField.textEditor.isEnabled = enabled
        typeComboBox.isEnabled = enabled && getSelectedConfigItem() != null
        contentArea.isEditable = enabled && contentLoaded
        publishButton.isEnabled = enabled && contentLoaded && getSelectedConfigItem() != null
        fetchLatestButton.isEnabled = enabled && getSelectedConfigItem() != null
        historyButton.isEnabled = enabled && contentLoaded && getSelectedConfigItem() != null
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

        private fun smallLabel(text: String) = JLabel(text).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
            putClientProperty("html.disable", true)
        }
    }
}
