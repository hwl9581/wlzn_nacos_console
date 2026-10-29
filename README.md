# WLZN Nacos Console

1.0.4 修复插件长时间打开后点击发布提示 `token expired`：确认发布后，使用当前连接的用户名和密码重新登录，再发送一次配置正文。登录或路由探测失败时停止；发布失败不自动重发，编辑草稿保留。

1.0.3 更新配置工作台：左侧按 Data ID / Group 展示紧凑列表，搜索放在列表顶部；右侧使用 IDEA 原生编辑器，提供语法高亮、行号、缩进线和软换行。配置格式切换保留正文与光标；搜索仍包含当前配置时保留草稿。注释与未注释的键值使用不同配色，YAML 注释统一映射到当前编辑器方案的普通行注释颜色，避免与字符串同色。实际配置是否生效仍取决于应用端加载逻辑。

选中已加载的配置，点击 **历史版本**：每页 50 条，可选择一个版本与打开历史窗口时的编辑内容快照对比，或选择两个历史版本互相比对。对比使用 IDEA 原生 Diff 窗口，两侧只读，不执行回滚或发布。版本 ID、环境、Data ID、Group 和 Namespace 固定到打开窗口时的目标；历史正文仅保存在内存中。历史清理、无权限或接口不可用会显示错误，不会把缺失正文当作空配置。

历史接口沿已连接的 Nacos 3 Console / Admin 路由；旧版先尝试 v2 历史接口，仅在 404/410 时尝试 v1。鉴权失败或业务错误不会降级，历史详情不会跨接口重试。需要目标 Namespace 的读取权限。

YAML、Properties、JSON、XML、HTML 高亮使用 IDEA 中已启用的对应语言支持；未启用时保留纯文本显示并提示。沿用既有插件图标及原有连接、发布确认流程。

在 IDEA 工具窗口中扫描 Spring Boot YAML、连接 Nacos、查看和发布配置。1.0.2 修复 Nacos 3 的 8848 服务端口连接：每个上下文先尝试 Console API、再尝试 Admin API，现代接口均不可用时才尝试该上下文的 Nacos 1/2 接口。只有只读列表探测返回 HTTP 404/410 时才尝试下一路由；鉴权失败、业务失败、异常响应或超时立即报错。

配置中的 `nacos.server-addr` 默认使用 `/nacos` 上下文，普通尾斜杠保持这一行为。裸地址还会在同端口尝试根上下文，以兼容独立控制台；地址已带 `/nacos` 或自定义路径时只在指定上下文内探测。不会猜测或切换端口。登录独立选择 auth 路径，不套用配置 API 路径；仍支持旧登录接口。

列表探测成功后，详情和发布使用同一 API 族；配置不存在的 404 不会触发换族。未选定路由时，发布先用只读列表确定路由，配置正文只 POST 一次。重连会重新探测。诊断日志记录探测路径和 HTTP 状态，不包含请求参数或正文。无需开启 Nacos 的旧控制台 API 兼容开关。

构建并运行本地模拟接口回归测试：

```bash
./gradlew test buildPlugin
# 可选：直接使用本机安装的 SDK（此处为 macOS 示例）
./gradlew test buildPlugin -PplatformLocalPath='/Applications/IntelliJ IDEA.app/Contents'
```

产物位于 `build/distributions/`。在 IDEA 的 Settings → Plugins → 齿轮 → Install Plugin from Disk 中选择 ZIP。默认测试只连接本机模拟服务，不会发布真实配置。

可选的真实服务只读验证从指定的本地 YAML 读取凭据，未指定时自动跳过；验证登录、列表及首条配置详情，不调用发布，也不输出凭据或配置正文。`WLZN_NACOS_SMOKE_PROFILES` 未设置时仅验证首个环境，多个环境用逗号分隔：

```bash
WLZN_NACOS_SMOKE_CONFIG=/absolute/path/application.yml WLZN_NACOS_SMOKE_PROFILES=dev,test \
  ./gradlew test --tests '*NacosApiClientLiveTest' -PplatformLocalPath='/Applications/IntelliJ IDEA.app/Contents'
```

安装后检查：扫描能发现新建/修改的 YAML，未保存内容可解析；连接后选择配置能加载正文；快速切换行时正文与 DataId/Group 一致；发布仍须确认目标环境。真实服务的权限、端口及配置写入需在测试环境验证。

以下保留原始模板的开发参考。

[![Twitter Follow](https://img.shields.io/badge/follow-%40JBPlatform-1DA1F2?logo=twitter)](https://twitter.com/JBPlatform)
[![Developers Forum](https://img.shields.io/badge/JetBrains%20Platform-Join-blue)][jb:forum]

## Plugin template structure

A generated project contains the following content structure:

```
.
├── .run/                   Predefined Run/Debug Configurations
├── build/                  Output build directory
├── gradle
│   ├── wrapper/            Gradle Wrapper
│   ├── libs.versions.toml  Version catalog
├── src                     Plugin sources
│   ├── main
│   │   ├── kotlin/         Kotlin production sources
│   │   └── resources/      Resources - plugin.xml, icons, messages
├── .gitignore              Git ignoring rules
├── build.gradle.kts        Gradle build configuration
├── gradle.properties       Gradle configuration properties
├── gradlew                 *nix Gradle Wrapper script
├── gradlew.bat             Windows Gradle Wrapper script
├── README.md               README
└── settings.gradle.kts     Gradle project settings
```

In addition to the configuration files, the most crucial part is the `src` directory, which contains our implementation
and the manifest for our plugin – [plugin.xml][file:plugin.xml].

> [!NOTE]
> To use Java in your plugin, create the `/src/main/java` directory.

## Plugin configuration file

The plugin configuration file is a [plugin.xml][file:plugin.xml] file located in the `src/main/resources/META-INF`
directory.
It provides general information about the plugin, its dependencies, extensions, and listeners.

You can read more about this file in the [Plugin Configuration File][docs:plugin.xml] section of our documentation.

If you're still not quite sure what this is all about, read our
introduction: [What is the IntelliJ Platform?][docs:intro]

$H$H Predefined Run/Debug configurations

Within the default project structure, there is a `.run` directory provided containing predefined *Run/Debug
configurations* that expose corresponding Gradle tasks:

| Configuration name | Description                                                                                                                                                                         |
|--------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Run Plugin         | Runs [`:runIde`][gh:intellij-platform-gradle-plugin-runIde] IntelliJ Platform Gradle Plugin task. Use the *Debug* icon for plugin debugging.                                        |
| Run Tests          | Runs [`:test`][gradle:lifecycle-tasks] Gradle task.                                                                                                                                 |
| Run Verifications  | Runs [`:verifyPlugin`][gh:intellij-platform-gradle-plugin-verifyPlugin] IntelliJ Platform Gradle Plugin task to check the plugin compatibility against the specified IntelliJ IDEs. |

> [!NOTE]
> You can find the logs from the running task in the `idea.log` tab.

## Publishing the plugin

> [!TIP]
> Make sure to follow all guidelines listed in [Publishing a Plugin][docs:publishing] to follow all recommended and
> required steps.

Releasing a plugin to [JetBrains Marketplace](https://plugins.jetbrains.com) is a straightforward operation that uses
the `publishPlugin` Gradle task provided by
the [intellij-platform-gradle-plugin][gh:intellij-platform-gradle-plugin-docs].

You can also upload the plugin to the [JetBrains Plugin Repository](https://plugins.jetbrains.com/plugin/upload)
manually via UI.

## Useful links

- [IntelliJ Platform SDK Plugin SDK][docs]
- [IntelliJ Platform Gradle Plugin Documentation][gh:intellij-platform-gradle-plugin-docs]
- [IntelliJ Platform Explorer][jb:ipe]
- [JetBrains Marketplace Quality Guidelines][jb:quality-guidelines]
- [IntelliJ Platform UI Guidelines][jb:ui-guidelines]
- [JetBrains Marketplace Paid Plugins][jb:paid-plugins]
- [IntelliJ SDK Code Samples][gh:code-samples]

[docs]: https://plugins.jetbrains.com/docs/intellij

[docs:intro]: https://plugins.jetbrains.com/docs/intellij/intellij-platform.html?from=IJPluginTemplate

[docs:plugin.xml]: https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html?from=IJPluginTemplate

[docs:publishing]: https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html?from=IJPluginTemplate

[file:plugin.xml]: ./src/main/resources/META-INF/plugin.xml

[gh:code-samples]: https://github.com/JetBrains/intellij-sdk-code-samples

[gh:intellij-platform-gradle-plugin]: https://github.com/JetBrains/intellij-platform-gradle-plugin

[gh:intellij-platform-gradle-plugin-docs]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html

[gh:intellij-platform-gradle-plugin-runIde]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html#runIde

[gh:intellij-platform-gradle-plugin-verifyPlugin]: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html#verifyPlugin

[gradle:lifecycle-tasks]: https://docs.gradle.org/current/userguide/java_plugin.html#lifecycle_tasks

[jb:github]: https://github.com/JetBrains/.github/blob/main/profile/README.md

[jb:forum]: https://platform.jetbrains.com/

[jb:quality-guidelines]: https://plugins.jetbrains.com/docs/marketplace/quality-guidelines.html

[jb:paid-plugins]: https://plugins.jetbrains.com/docs/marketplace/paid-plugins-marketplace.html

[jb:quality-guidelines]: https://plugins.jetbrains.com/docs/marketplace/quality-guidelines.html

[jb:ipe]: https://jb.gg/ipe

[jb:ui-guidelines]: https://jetbrains.github.io/ui
