plugins {
    id("java")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intellijPlatform)
}

group = "com.wlzn"
version = "1.0.2"

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmDefault = org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    implementation("org.yaml:snakeyaml:2.2")
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        val localPath = providers.gradleProperty("platformLocalPath").orNull
        if (localPath != null) local(localPath) else intellijIdea(providers.gradleProperty("platformVersion"))
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }

        changeNotes = """
            <ul>
                <li><b>1.0.2</b> — Fix Nacos 3 server-port connections using Admin APIs, support root-context consoles, and discover routes with read-only requests before publishing.</li>
                <li><b>1.0.1</b> — Support Nacos 3 console APIs with legacy fallback; load configuration details safely and scan files in the background.</li>
                <li><b>1.0.0</b> — Initial release: multi-environment config parsing, config browsing, refresh &amp; publish.</li>
            </ul>
        """.trimIndent()
    }

    buildSearchableOptions = false

    pluginVerification {
        ides {
            val localPath = providers.gradleProperty("platformLocalPath").orNull
            if (localPath != null) local(file(localPath)) else recommended()
        }
    }
}

tasks {
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }
}
