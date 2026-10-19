# 开发与发布

## 环境

- JDK 17，需包含 `jpackage`；Gradle 通过仓库 Wrapper 下载。
- Windows EXE 使用 Inno Setup 6。编译器按常见安装位置查找，也可传入 `-PinnoHome=<安装目录>`。
- 首次构建需要联网下载依赖；已有完整缓存时可使用 `--offline`。

```powershell
.\gradlew.bat :composeApp:run
.\gradlew.bat :core:test :scene:test :composeApp:test
.\gradlew.bat :composeApp:packageWindowsInstaller --no-daemon '-Pkotlin.compiler.execution.strategy=in-process' --max-workers=2
```

安装包输出到 `composeApp/build/compose/binaries/main/dist/`。`--no-daemon` 配合进程内 Kotlin 编译可避免构建进程长期驻留。重新打包前关闭从构建目录启动的测试程序，防止文件被占用。

Windows 系统编码与 jpackage 参数文件编码可能不一致。项目路径含中文且构建失败时，建议使用纯 ASCII 路径检出仓库，或在 PowerShell/cmd 中通过纯 ASCII 目录联接构建。不要全局添加 `-Dfile.encoding=UTF-8` 来绕过路径问题。

`packaging/windows/setup.iss` 必须保留 UTF-8 BOM，以便 Inno Setup 正确读取中文。

## 命令行工具

```powershell
.\gradlew.bat :composeApp:cli --args="sample/texture.tex out.png"
.\gradlew.bat :composeApp:cliJar
java -jar composeApp/build/libs/mpkg-cli.jar --help
```

支持指定输出目录、缩放、预览提取、逐像素对比和覆盖策略，具体选项以 `--help` 为准。

## 发布版本

1. 同步修改 `AppInfo.kt` 的 `VERSION`、`composeApp/build.gradle.kts` 中的包版本与安装包输出文件名、`packaging/windows/setup.iss` 的 `MyAppVersion`。
2. 运行三个模块测试并构建 EXE，核对安装、文件关联、导入、转换、文件定位及关于页。
3. 将 EXE 上传至发布者的下载位置，例如 GitHub Releases。
4. 更新根目录 `update.json` 的 `version`、`downloadUrl` 和 `notes`，同步至更新检查服务所在的仓库。仅推送 GitHub 不会自动同步 Gitee。

更新服务地址由 `composeApp/src/main/kotlin/com/mpkg/app/update/UpdateConfig.kt` 维护，不在用户界面提供编辑入口。下载地址优先读取更新清单；为空时使用内置回退地址。发布时应填写可用的下载链接。

`force: false` 表示普通更新；`force: true` 会禁用更新公告的忽略与关闭操作，发布前应确认下载可用。

## 图标与许可

应用图标源文件为 `composeApp/src/main/resources/icons/brand-source.png`。安装 Pillow 后运行 `python scripts/make-icons.py composeApp/src/main/resources/icons` 可重新生成各尺寸图标。

保留 `LICENSE`、`packaging/windows/安装许可.txt` 和 `packaging/windows/THIRD-PARTY-NOTICES.txt`。后两者随 Windows 安装包分发。
