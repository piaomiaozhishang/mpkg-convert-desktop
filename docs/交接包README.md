# 桌面版交接包 —— 给接手的 Agent

## 任务

把一套**已上线的 Android 壁纸提取器**的核心转换技术，做成 **Windows/macOS/Linux 桌面版**。

- 功能：解析 Wallpaper Engine 的 `.mpkg` / `.pkg` 壁纸包 → 提取 **4K 原图（PNG/JPEG，无损）**、**MP4 视频（字节无损，不重编码）**、内嵌图片
- Android 版已上线并开源（UI/交互可参考，但你的任务是全新的桌面端）：
  <https://github.com/piaomiaozhishang/mpkg-convert>
- **核心解析/解码代码是纯 JVM 的、零 Android 依赖**，可以直接复用；本包还附了零依赖的
  Python 参考实现（非 JVM 技术栈时对照移植用）。

---

## 目录导览

| 路径 | 是什么 |
|---|---|
| `docs/技术总结与移植指南.md` | **先读这个**：架构、格式要点、十大坑、Android→桌面映射表、验收标准 |
| `docs/FORMAT_SPEC.md` | `.mpkg` / `.tex` / ETC2_RGBA8 的**逐位格式规范**（已用真实 4K 壁纸逐位验证，并用 Khronos 规范与 RePKG 源码交叉核对） |
| `docs/COMPAT_FORMATS.md` | 支持的纹理/图片格式清单 |
| `docs/CROSS_CHECK.md` | 两种独立实现的交叉验证记录 |
| `docs/ANDROID_PORTING.md` | 当初移植到 Android 时的记录（坑位对照） |
| `docs/方法对比与融合建议.md` | 两条技术路线的对比结论 |
| `kotlin-core/` | **生产级核心源码**（纯 JVM Kotlin，8 个文件，来自已上架 App） |
| `kotlin-tests/` | 4 个测试类 + 测试向量（testdata/ 放进 `src/test/resources/testdata/` 即可跑） |
| `python-reference/` | **零依赖 Python 参考实现**：端到端 `.mpkg`→PNG、纯 ETC2 解码器、LZ4 block 解码器 |
| `android-reference/` | Android 专属实现（GLES GPU 解码），桌面端只作思路参考 |
| `sample/` | 真实样本：`texture.tex`（4K 壁纸纹理）+ **期望输出 PNG** + preview.jpg |
| `testdata/` | 测试向量：LZ4 用例、ETC2 自测块 + 期望输出、SHA256 清单、验证记录 |

---

## 快速上手（三条路径选一条）

### A. 你用 JVM 技术栈（Kotlin / Java）→ 核心直接复用

```bash
# 1. 编译（kotlinc 2.x + JDK17 + lz4-java）
kotlinc kotlin-core/*.kt -cp lz4-java-1.8.0.jar -include-runtime -d mpkg-core.jar

# 2. 跑测试（把 kotlin-tests/testdata 放到 src/test/resources/testdata/）
#    DxtTest / Etc2CpuDecoderTest / MainImageTest / PkgExtractorTest —— 全绿才算接住了

# 3. 端到端：解 sample/texture.tex，输出应与 sample/expected_*.png 逐像素一致
```

> `kotlin-core` 里唯一的第三方依赖是 `org.lz4:lz4-java:1.8.0`（LZ4 block 解压）。
> `AndroidLz4.kt` 名字里有 Android，其实是纯 JVM 的（只是当初在 App 里写的），建议改名 `Lz4Java.kt`。

### B. 你用其它语言（C# / Rust / Go / Python…）

`python-reference/` 就是**能直接跑的参考实现**（零第三方依赖）：

```bash
python python-reference/extract_original.py sample/texture.tex out.png
# out.png 应与 sample/expected_1280x720.png 逐像素一致（0 差异）
```

按 `docs/FORMAT_SPEC.md` 逐位移植即可，位级细节（bit 排布、大小端、索引顺序）都写全了。

### C. 只想先看懂格式 → 读 `docs/FORMAT_SPEC.md`

---

## 核心结论 TOP 10（不读会翻车的十条）

1. **`Format == 5` = ETC2_RGBA8**。RePKG/linux-wallpaperengine 的枚举里没有 5，会直接报错拒开。
2. ETC2_RGBA8 的 16 字节块 = `[8B EAC alpha][8B ETC2 RGB]`，**两半都是大端 64 位**。
3. 全不透明的 alpha 半块恒为 `FF 00 00 00 00 00 00 00` —— 一眼识别格式的指纹。
4. **解码结果就是 RGBA，不要交换 R/B**。Python 库 `texture2ddecoder` 返回 BGRA 会骗你以为数据是 BGR。
5. `.tex` 的 mip 用的是**裸 LZ4 block**（无 frame 头、无魔数），解压目标长度由 mip 头给出。
6. **TEXB0004 且非 MP4 时，mip 记录按旧版 TEXB0003 布局解析**（多出的参数只在 MP4 时存在）——搞错这个，mip 头看起来就是乱码。
7. `preview.jpg` 是**缩略图**，不是原图；主图要用 `ImageRanker.kt` 的打分逻辑选（避免取到预览小图/遮罩/粒子拖尾）。
8. 视频：`.mp4` 条目直接字节导出；TEXB0004 视频纹理也是字节级提取，**绝不重编码**。
9. 内嵌 PNG(13)/JPEG(2)/GIF(25) 的 mip 数据**本身就是完整图片文件**，原样写出即可。
10. PKGV（桌面 .pkg）与 PKGM（手机 .mpkg）**共用同一套容器布局**，一个解析器通吃。

---

## 验收标准

1. `kotlin-tests` 4 个测试类全部通过（对照 `testdata/`）。
2. `sample/texture.tex` 解出的图与 `sample/expected_1280x720.png` **逐像素一致**
   （Python 参考实现做到了 24,883,200 个值 0 差异；你的实现至少要 NCC ≈ 1.0）。
3. 用你自己的 Wallpaper Engine 导出几个真实 `.mpkg`（含视频壁纸）：
   原图正确、MP4 能播、文件字节与包内一致。
4. 乱数据/截断文件**不崩溃**，报格式错误。

---

## 许可与出处（发布时必须保留）

| 代码 | 出处 | 许可 |
|---|---|---|
| `kotlin-core/Dxt.kt` | 移植自 [RePKG](https://github.com/notscuffed/repkg)（S3TC 解压，源自 LibSquish） | MIT |
| `kotlin-core/ImageRanker.kt` | 移植自社区 dsh-mpkg-wallpaper 插件 | MIT |
| 其余 core 代码 | 本项目原创（逆向 + Khronos 规范交叉验证） | MIT |

格式知识来源：Wallpaper Engine 逆向 + Khronos OpenGL ES 数据格式规范 + Android DEQP 一致性测试 + [yakisoba0728/Waple](https://github.com/yakisoba0728/Waple) 的格式映射。

---

## 一句话状态

容器解析 ✅ 纹理解码 ✅（CPU 全格式 + GPU 参考） 主图打分 ✅ 视频提取 ✅ 测试向量 ✅
真实样本 + 期望输出 ✅ —— **核心全部就绪，你只需要做桌面壳（文件选择、批量、UI、打包）。**
