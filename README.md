# mpkg 壁纸提取器

解析 Wallpaper Engine 的 `.mpkg` / `.pkg` 壁纸包，提取图片、MP4 视频和预览图，并合成静态场景壁纸。

当前版本：**1.0.1**。已验证平台：Windows x64，安装包内置运行时，无需额外安装 Java。

## 下载与使用

从 [GitHub Releases](https://github.com/piaomiaozhishang/mpkg-convert-desktop/releases) 获取 `mpkg-tool-setup-1.0.1.exe`，按中文安装向导安装。支持自定义安装位置、桌面快捷方式及系统卸载。

1. 拖入 `.mpkg` / `.pkg`，或通过 Windows 原生文件选择窗口批量导入。
2. 确认输出目录，在设置中选择覆盖策略。
3. 点击“开始转换”，等待任务完成。
4. 点击文件夹按钮定位输出文件，或打开文件预览。

设置和历史记录保存在 `%APPDATA%\mpkg-tool`。支持浅色/深色主题、手动及自动检查更新。

快捷键：`Ctrl+O` 导入文件，`Ctrl+,` 打开设置，`Delete` 移除选中任务，`Esc` 取消转换。

## 支持范围

| 内容 | 输出与限制 |
| --- | --- |
| 图片纹理 | 支持 ETC2、DXT 等已实现的格式，导出 PNG；内嵌图片可原样提取 |
| 已有视频 | 原样提取 MP4，不重编码 |
| 静态场景 | 合成 PNG，支持图层尺寸、位置、旋转、可见性、颜色、透明度及 Opacity 遮罩 |
| 动态特效 | 尚未实现动态场景转 MP4；未渲染的特效会在合成说明中列出 |

TEXF 动帧、ASTC、BC7、加密包及网页/代码壁纸不在当前完整支持范围内。GPU 解码未实现；macOS/Linux 尚未完成发布验证。

更多边界与动态特效评估见 [场景能力说明](docs/场景特效可行性与本次修复.md)。

## 开发

技术栈：Kotlin、Compose Multiplatform Desktop、JDK 17。

```powershell
.\gradlew.bat :composeApp:run
.\gradlew.bat :core:test :scene:test :composeApp:test
.\gradlew.bat :composeApp:packageWindowsInstaller
```

Windows EXE 构建需要 Inno Setup 6。构建、命令行工具和更新发布步骤见 [开发与发布指南](docs/BUILD.md)。底层包格式见 [格式规范](docs/FORMAT_SPEC.md)。

| 目录 | 用途 |
| --- | --- |
| `core/` | 包解析与纹理解码 |
| `scene/` | 场景解析与静态合成 |
| `composeApp/` | 桌面界面、任务队列、系统集成与 CLI |
| `packaging/` | 安装脚本与许可文件 |
| `scripts/` | 构建辅助、图标生成与验证工具 |

## 问题反馈

可在 [哔哩哔哩视频评论区](https://b23.tv/JWQpitx) 提出 bug 反馈与建议。请附软件版本、复现步骤和错误提示；诊断信息可从“关于”页复制，分享前请检查其中的本地路径。

## 许可

项目采用 [MIT License](LICENSE)。第三方组件说明见 [THIRD-PARTY-NOTICES](packaging/windows/THIRD-PARTY-NOTICES.txt)，安装包随附相关许可文件。
