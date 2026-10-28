package com.wlzn.nacos.ui

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class NacosConfigEditorTest : BasePlatformTestCase() {
    fun testYamlCommentsKeysAndValuesUseNativeHighlightingInBothThemes() {
        val colors = EditorColorsManager.getInstance()
        val original = colors.globalScheme
        val component = NacosConfigEditor(project)
        Disposer.register(testRootDisposable, component)
        component.setFormat("yaml")
        component.text = "# enabled: false\nenabled: true\nname: \"demo\"\nurl: \"https://example.com/#fragment\"\n"
        try {
            for (name in listOf("Default", "Darcula")) {
                val scheme = colors.getScheme(name) ?: error("Missing bundled color scheme: $name")
                component.editor.colorsScheme = scheme
                component.setFormat("yaml")
                assertTrue(component.highlightingAvailable)
                val highlighter = component.editor.highlighter
                val comment = highlighter.createIterator(2).textAttributes.foregroundColor
                val key = highlighter.createIterator(component.text.indexOf("enabled: true")).textAttributes.foregroundColor
                val value = highlighter.createIterator(component.text.indexOf("demo")).textAttributes.foregroundColor
                assertNotNull(comment)
                assertNotNull(key)
                assertNotNull(value)
                assertFalse("Comments must differ from active keys in $name", comment == key)
                assertFalse("Comments must differ from quoted values in $name", comment == value)
                assertFalse("Keys must differ from strings in $name", key == value)
                val quotedHash = highlighter.createIterator(component.text.indexOf("#fragment")).textAttributes.foregroundColor
                assertEquals("A hash inside a quoted URL must stay part of the value", value, quotedHash)
            }
        } finally {
            component.editor.colorsScheme = original
        }
    }

    fun testFormatSwitchKeepsDocumentContentCaretAndReadOnlyState() {
        val component = NacosConfigEditor(project)
        Disposer.register(testRootDisposable, component)
        component.text = "# demo\nfeature.enabled=true\n"
        component.editor.caretModel.moveToOffset(10)
        val document = component.editor.document
        for (format in listOf("properties", "json", "yaml", "text", "unknown-format")) {
            component.setFormat(format)
            assertSame(document, component.editor.document)
            assertEquals("# demo\nfeature.enabled=true\n", component.text)
            assertEquals(10, component.editor.caretModel.offset)
            assertFalse(component.isEditable)
        }
        assertEquals("text", component.format)
    }

    fun testPropertiesCommentAndValueHaveDifferentColors() {
        val component = NacosConfigEditor(project)
        Disposer.register(testRootDisposable, component)
        component.setFormat("properties")
        component.text = "# enabled=false\nenabled=true\n"
        assertTrue(component.highlightingAvailable)
        val comment = component.editor.highlighter.createIterator(2).textAttributes.foregroundColor
        val value = component.editor.highlighter.createIterator(component.text.indexOf("true")).textAttributes.foregroundColor
        assertFalse(comment == value)
    }

    fun testRemoteLoadBlocksUndoFromPreviousConfigurationAndEditorIsReleased() {
        val component = NacosConfigEditor(project)
        var editor = component.editor
        try {
            component.text = "first: original\n"
            editor = component.editor
            component.isEditable = true
            WriteCommandAction.runWriteCommandAction(project) { editor.document.setText("first: edited\n") }
            component.text = "second: untouched\r\n"
            assertTrue("The previous configuration editor must be released", editor.isDisposed)
            editor = component.editor
            assertEquals("second: untouched\n", component.text)
            assertFalse("A previous configuration must not be restored by Undo", UndoManager.getInstance(project).isUndoAvailable(TextEditorProvider.getInstance().getTextEditor(editor)))
        } finally {
            Disposer.dispose(component)
        }
        assertTrue(editor.isDisposed)
    }
}
