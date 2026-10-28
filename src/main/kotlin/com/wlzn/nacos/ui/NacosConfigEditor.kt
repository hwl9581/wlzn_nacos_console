package com.wlzn.nacos.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.psi.tree.IElementType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import java.awt.BorderLayout
import javax.swing.JPanel

/** 独立内存文档，不关联项目文件，也不把远端配置写入磁盘。 */
internal class NacosConfigEditor(private val project: Project) : JPanel(BorderLayout()), Disposable {
    var onTextChanged: (Int) -> Unit = {}
    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            if (event.document === editor.document) onTextChanged(event.document.lineCount)
        }
    }
    var editor: EditorEx = createEditor("")
        private set
    var format: String = "text"
        private set
    var highlightingAvailable = true
        private set

    var text: String
        get() = editor.document.text
        set(value) {
            // 每次远端载入使用新内存文档，从根本上隔离不同 Data ID 的撤销和重做记录。
            val previous = editor
            val editable = isEditable
            editor = createEditor(StringUtil.convertLineSeparators(value))
            isEditable = editable
            setFormat(format)
            remove(previous.component)
            EditorFactory.getInstance().releaseEditor(previous)
            add(editor.component, BorderLayout.CENTER)
            revalidate()
            repaint()
            onTextChanged(editor.document.lineCount)
        }

    var isEditable: Boolean
        get() = !editor.isViewer
        set(value) { editor.isViewer = !value }

    private fun createEditor(text: String): EditorEx {
        val created = EditorFactory.getInstance().createEditor(
            EditorFactory.getInstance().createDocument(text), project, PlainTextFileType.INSTANCE, false
        ) as EditorEx
        created.settings.apply {
            isLineNumbersShown = true
            isIndentGuidesShown = true
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = false
            isRightMarginShown = false
            isAdditionalPageAtBottom = false
            additionalLinesCount = 2
            setTabSize(2)
            isUseSoftWraps = true
        }
        created.setPlaceholder("选择左侧配置，查看并编辑内容")
        created.setShowPlaceholderWhenFocused(true)
        return created
    }

    init {
        isEditable = false
        add(editor.component, BorderLayout.CENTER)
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(documentListener, this)
        project.messageBus.connect(this).subscribe(EditorColorsManager.TOPIC, EditorColorsListener {
            setFormat(format)
        })
    }

    fun setFormat(type: String) {
        format = normalizeFormat(type)
        val extension = if (format == "text") "txt" else format
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension(extension)
        highlightingAvailable = format == "text" || fileType != PlainTextFileType.INSTANCE && !fileType.isBinary
        val factory = EditorHighlighterFactory.getInstance()
        val syntax = if (format == "yaml") SyntaxHighlighterFactory.getSyntaxHighlighter(fileType, project, null) else null
        editor.highlighter = if (syntax != null) {
            // YAML 默认将 # 注释映射为文档注释，部分主题会与字符串同色。
            // 只在此编辑器中改用普通行注释配色，词法解析仍由 IDEA 完成。
            factory.createEditorHighlighter(object : SyntaxHighlighter {
                override fun getHighlightingLexer() = syntax.highlightingLexer
                override fun getTokenHighlights(tokenType: IElementType) = syntax.getTokenHighlights(tokenType).map {
                    if (it.externalName == "YAML_COMMENT") DefaultLanguageHighlighterColors.LINE_COMMENT else it
                }.toTypedArray()
            }, editor.colorsScheme)
        } else factory.createEditorHighlighter(if (fileType.isBinary) PlainTextFileType.INSTANCE else fileType, editor.colorsScheme, project)
    }

    override fun dispose() {
        if (!editor.isDisposed) EditorFactory.getInstance().releaseEditor(editor)
    }

    companion object {
        fun normalizeFormat(type: String): String = when (val value = type.lowercase()) {
            "yml" -> "yaml"
            "yaml", "properties", "json", "xml", "html" -> value
            else -> "text"
        }
    }
}
