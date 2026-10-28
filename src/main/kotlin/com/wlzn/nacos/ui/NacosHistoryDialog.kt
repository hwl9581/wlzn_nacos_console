package com.wlzn.nacos.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.wlzn.nacos.model.NacosConfigItem
import com.wlzn.nacos.model.NacosEnvironment
import com.wlzn.nacos.model.NacosHistoryItem
import com.wlzn.nacos.service.NacosApiClient
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/** 环境、配置及编辑内容在打开时固定，对比仅在内存中进行，不执行回滚或发布。 */
internal class NacosHistoryDialog(
    private val project: Project,
    private val apiClient: NacosApiClient,
    private val environment: NacosEnvironment,
    private val item: NacosConfigItem,
    private val token: String,
    private val currentText: String,
    private val format: String
) : DialogWrapper(project) {
    private val model = object : DefaultTableModel(arrayOf("版本 ID", "修改时间", "操作", "发布者", "发布类型"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val table = JBTable(model)
    private val status = JLabel("正在读取历史版本…")
    private val pageLabel = JLabel("第 1 页")
    private val previousButton = JButton("上一页")
    private val nextButton = JButton("下一页")
    private val refreshButton = JButton("刷新")
    private val compareCurrentButton = JButton("与当前编辑内容对比")
    private val compareVersionsButton = JButton("对比选中版本")
    private var records = emptyList<NacosHistoryItem>()
    private var page = 1
    private var totalCount = 0
    private var pagesAvailable = 0
    private var busy = true

    init {
        title = "历史版本 · ${item.dataId}"
        setCancelButtonText("关闭")
        init()
        setSize(JBUI.scale(940), JBUI.scale(560))
        previousButton.addActionListener { loadPage(page - 1) }
        nextButton.addActionListener { loadPage(page + 1) }
        refreshButton.addActionListener { loadPage(page) }
        compareCurrentButton.addActionListener { compare(false) }
        compareVersionsButton.addActionListener { compare(true) }
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) updateButtons() }
        updateButtons()
        ApplicationManager.getApplication().invokeLater({ if (!isDisposed) loadPage(1) }, ModalityState.any())
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(10))).apply {
        border = JBUI.Borders.empty(8)
        add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(JLabel("${environment.profileName} · ${item.dataId} · ${item.group} · namespace: ${environment.namespace.ifBlank { "public" }}").apply {
                putClientProperty("html.disable", true)
            }, BorderLayout.NORTH)
            add(JLabel("选择 1 个版本与打开窗口时的编辑内容对比，或选择 2 个历史版本互相比对。"), BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        table.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        table.rowHeight = JBUI.scale(30)
        table.showHorizontalLines = false
        table.showVerticalLines = false
        table.tableHeader.reorderingAllowed = false
        table.emptyText.text = "暂无历史版本"
        table.setDefaultRenderer(Any::class.java, DefaultTableCellRenderer().apply { putClientProperty("html.disable", true) })
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                add(previousButton); add(pageLabel); add(nextButton); add(refreshButton)
                add(compareCurrentButton); add(compareVersionsButton)
            }, BorderLayout.NORTH)
            status.putClientProperty("html.disable", true)
            status.foreground = JBColor.GRAY
            add(status, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
    }

    private fun loadPage(targetPage: Int) {
        if (targetPage < 1 || isDisposed || project.isDisposed) return
        busy = true
        status.text = "正在读取第 $targetPage 页历史版本…"
        updateButtons()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val result = apiClient.getHistoryList(environment.serverAddr, item.dataId, item.group, environment.namespace, token, targetPage)
                later {
                    page = targetPage
                    totalCount = result.totalCount
                    pagesAvailable = result.pagesAvailable
                    records = result.pageItems.orEmpty()
                    model.rowCount = 0
                    records.forEach { record -> model.addRow(arrayOf(record.id, displayTime(record.modifiedTime),
                        when (record.opType?.trim()) { "I" -> "新增"; "U" -> "修改"; "D" -> "删除"; else -> record.opType.orEmpty() },
                        record.srcUser ?: "—", if (record.publishType == "gray") "灰度" else "正式")) }
                    pageLabel.text = "第 $page 页 · 共 $totalCount 条"
                    busy = false
                    status.text = if (records.isEmpty()) "暂无历史记录，或服务端已清理历史版本" else "历史版本为只读；对比不会更改编辑内容或线上配置"
                    updateButtons()
                }
            } catch (e: Exception) {
                later { busy = false; status.text = "读取历史版本失败：${e.message}"; updateButtons() }
            }
        }
    }

    private fun compare(twoVersions: Boolean) {
        if (busy || isDisposed || project.isDisposed) return
        val selected = table.selectedRows.map { records[it] }.sortedBy { it.id.toLongOrNull() ?: 0 }
        if (selected.size != if (twoVersions) 2 else 1) return
        busy = true
        status.text = "正在读取历史版本正文…"
        updateButtons()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val versions = selected.map { record ->
                    apiClient.getHistory(environment.serverAddr, item.dataId, item.group, environment.namespace, token, record.id)
                }
                later {
                    busy = false
                    updateButtons()
                    val left = versions.first()
                    val right = versions.getOrNull(1)
                    val request = createDiffRequest(project, item, format, left.content.orEmpty(), right?.content ?: currentText,
                        "版本 ${left.id} · ${displayTime(left.modifiedTime)}", right?.let { "版本 ${it.id} · ${displayTime(it.modifiedTime)}" } ?: "当前编辑内容（打开历史窗口时的快照）")
                    status.text = "对比为只读，不会修改配置"
                    DiffManager.getInstance().showDiff(project, request, DiffDialogHints.MODAL)
                }
            } catch (e: Exception) {
                later { busy = false; status.text = "读取历史正文失败：${e.message}"; updateButtons() }
            }
        }
    }

    private fun updateButtons() {
        previousButton.isEnabled = !busy && page > 1
        nextButton.isEnabled = !busy && (page * 50 < totalCount || page < pagesAvailable)
        refreshButton.isEnabled = !busy
        table.isEnabled = !busy
        compareCurrentButton.isEnabled = !busy && table.selectedRowCount == 1
        compareVersionsButton.isEnabled = !busy && table.selectedRowCount == 2
    }

    private fun later(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ if (!isDisposed && !project.isDisposed) action() }, ModalityState.any())
    }

    companion object {
        internal fun createDiffRequest(project: Project, item: NacosConfigItem, format: String,
                                       left: String, right: String, leftTitle: String, rightTitle: String): SimpleDiffRequest {
            val fileType = FileTypeManager.getInstance().getFileTypeByExtension(NacosConfigEditor.normalizeFormat(format).let { if (it == "text") "txt" else it })
            val type = if (fileType.isBinary) PlainTextFileType.INSTANCE else fileType
            val factory = DiffContentFactory.getInstance()
            return SimpleDiffRequest("${item.dataId} · ${item.group}", factory.create(project, left, type), factory.create(project, right, type), leftTitle, rightTitle).apply {
                putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
                putUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS, booleanArrayOf(true, true))
            }
        }

        private fun displayTime(value: String?): String {
            val millis = value?.toLongOrNull() ?: return value ?: "—"
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))
        }
    }
}
