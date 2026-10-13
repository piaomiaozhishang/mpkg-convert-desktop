import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// composeApp 模块：Compose Multiplatform Desktop 桌面壳（见《02 · 架构与目录结构》第 2 节）。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    // 单元测试不碰真实屏幕：ImageIO / BufferedImage 在无头模式下足够用。
    systemProperty("java.awt.headless", "true")
    systemProperty("file.encoding", "UTF-8")
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// 统一把 JavaExec 的工作目录固定到项目根：文档里的命令一律使用根目录相对路径
// （如 `--args="sample/texture.tex out.png"`），默认的模块目录会让这些路径失效。
tasks.withType<JavaExec>().configureEach {
    workingDir = rootProject.projectDir
}

compose.desktop {
    application {
        mainClass = "com.mpkg.app.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "mpkg-tool"
            packageVersion = "1.0.0"
            description = "Wallpaper Engine mpkg/pkg 壁纸提取器"
            vendor = "mpkg-tool"
            copyright = "MIT License"
            // 安装程序里展示的许可（T4.2 要求许可完整保留）
            licenseFile.set(rootProject.file("LICENSE"))

            // 文件关联（T4.1）：双击 .mpkg / .pkg 唤起应用并直接入队。
            // Main.kt 的 isGraphicalLaunch() 负责把命令行文件参数转成队列任务。
            windows {
                menu = true
                shortcut = true
                // 升级用固定 GUID，重新打包后仍能覆盖安装。
                upgradeUuid = "6F1D2B4E-7A3C-4E5B-9C8D-1A2B3C4D5E6F"
                iconFile.set(project.file("src/main/resources/icons/app.ico"))
                fileAssociation("application/x-mpkg", "mpkg", "Wallpaper Engine 手机端壁纸包")
                fileAssociation("application/x-we-pkg", "pkg", "Wallpaper Engine 桌面端工程包")
            }
            linux {
                shortcut = true
                appCategory = "Graphics"
                menuGroup = "Graphics"
                iconFile.set(project.file("src/main/resources/icons/app_256.png"))
                fileAssociation("application/x-mpkg", "mpkg", "Wallpaper Engine 手机端壁纸包")
                fileAssociation("application/x-we-pkg", "pkg", "Wallpaper Engine 桌面端工程包")
            }
            macOS {
                iconFile.set(project.file("src/main/resources/icons/app.icns"))
                bundleID = "io.github.piaomiaozhishang.mpkgtool"
                // macOS 的文件关联需要改 Info.plist，jpackage 不直接支持，
                // 这里只登记图标与包信息，README 已明示该限制。
            }
        }
    }
}

/** 端到端 CLI（T1.3）：`./gradlew :composeApp:cli --args="sample/texture.tex out.png"`。 */
tasks.register<JavaExec>("cli") {
    group = "application"
    description = "运行端到端命令行转换（不打开窗口）"
    mainClass.set("com.mpkg.app.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

/** PNG 逐像素比对工具（T1.3）。 */
tasks.register<JavaExec>("pngCompare") {
    group = "verification"
    description = "逐像素比对两个 PNG：--args=\"<a.png> <b.png>\""
    mainClass.set("com.mpkg.app.tools.PngCompareKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
}

/** 可独立运行的 CLI jar（只带 core/lz4/kotlin-stdlib，不需要 Compose）。 */
tasks.register<Jar>("cliJar") {
    group = "application"
    description = "打包可 java -jar 运行的命令行版"
    archiveFileName.set("mpkg-cli.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    manifest { attributes("Main-Class" to "com.mpkg.app.tools.CliMainKt") }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets["main"].output)
    from({
        configurations.runtimeClasspath.get()
            .filter {
                // 只带 CLI 真正需要的：core 模块、lz4-java、kotlin-stdlib。
                // core 模块的 jar 名就是 "core.jar"（无版本号），不要用前缀匹配漏掉。
                val n = it.name
                n == "core.jar" || n.startsWith("core-") || n.startsWith("lz4-java") ||
                    n.startsWith("kotlin-stdlib") || n.startsWith("annotations-")
            }
            .map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**")
    }
}
