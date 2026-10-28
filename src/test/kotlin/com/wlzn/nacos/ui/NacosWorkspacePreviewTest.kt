package com.wlzn.nacos.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import com.wlzn.nacos.model.NacosConfigItem
import com.wlzn.nacos.model.NacosEnvironment
import java.awt.Container
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.UIManager

/** 只渲染本地虚构数据，不读取当前项目配置，也不连接 Nacos。 */
class NacosWorkspacePreviewTest : BasePlatformTestCase() {
    fun testRenderNativeWorkspaceWithOnlyFictionalConfiguration() {
        val originalLaf = UIManager.getLookAndFeel()
        val originalDark = !JBColor.isBright()
        val colors = EditorColorsManager.getInstance()
        val originalScheme = colors.globalScheme
        val output = Path.of("build/reports/ui")
        Files.createDirectories(output)
        com.intellij.openapi.util.IconLoader.activate()
        try {
            for (dark in listOf(false, true)) {
                if (dark) {
                    UIManager.setLookAndFeel("com.intellij.ide.ui.laf.darcula.DarculaLaf")
                    com.intellij.ide.ui.LafManager.getInstance().defaultDarkLaf?.installTheme(UIManager.getLookAndFeelDefaults())
                }
                JBColor.setDark(dark)
                val panel = NacosConsolePanel(project)
                try {
                    field<JComboBox<String>>(panel, "configFileComboBox").addItem("src/main/resources/application.yaml")
                    field<JComboBox<NacosEnvironment>>(panel, "envComboBox").addItem(NacosEnvironment("dev", "https://nacos.example.com", "demo-dev", "", ""))
                    val items = listOf(
                        NacosConfigItem(dataId = "demo-service.yaml", group = "DEMO_GROUP", type = "yaml"),
                        NacosConfigItem(dataId = "demo-gateway.yaml", group = "DEMO_GROUP", type = "yaml"),
                        NacosConfigItem(dataId = "shared.properties", group = "DEFAULT_GROUP", type = "properties"),
                        NacosConfigItem(dataId = "feature-flags.json", group = "DEFAULT_GROUP", type = "json")
                    )
                    NacosConsolePanel::class.java.getDeclaredMethod("updateConfigList", List::class.java).apply { isAccessible = true }.invoke(panel, items)
                    field<JList<NacosConfigItem>>(panel, "configList").selectedIndex = 0
                    val content = field<NacosConfigEditor>(panel, "contentArea")
                    content.text = "# 仅用于界面验证的模拟配置\nserver:\n  port: 8080\n\nspring:\n  application:\n    name: demo-service\n\n# 注释不会作为配置读取\n# feature.enabled: false\nfeature:\n  enabled: true\n  timeout: 3000  # 超时时间，单位 ms\n\nintegration:\n  endpoint: \"https://api.example.com\"\n  retry-count: 3\n"
                    content.editor.colorsScheme = colors.getScheme(if (dark) "Darcula" else "Default") ?: originalScheme
                    content.setFormat("yaml")
                    field<JLabel>(panel, "statusLabel").text = "模拟数据预览 · 未访问任何真实服务"
                    for (width in listOf(1024, 1440)) {
                        panel.setSize(width, 640)
                        repeat(3) { layout(panel) }
                        UIUtil.dispatchAllInvocationEvents()
                        val image = BufferedImage(width, 640, BufferedImage.TYPE_INT_RGB)
                        image.createGraphics().let { g -> try { panel.printAll(g) } finally { g.dispose() } }
                        ImageIO.write(image, "png", output.resolve("nacos-${if (dark) "dark" else "light"}-$width.png").toFile())
                    }
                } finally {
                    Disposer.dispose(panel)
                }
            }
        } finally {
            UIManager.setLookAndFeel(originalLaf)
            JBColor.setDark(originalDark)
        }
    }

    private fun layout(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach { layout(it) }
    }

    private inline fun <reified T> field(panel: NacosConsolePanel, name: String): T =
        NacosConsolePanel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(panel) as T
}
