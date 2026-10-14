package com.mpkg.app.settings

import com.mpkg.app.export.OverwritePolicy
import com.mpkg.app.platform.PlatformPaths
import com.mpkg.app.ui.theme.AccentPreset
import com.mpkg.app.ui.theme.ThemeMode
import com.mpkg.app.util.AppLog
import com.mpkg.app.util.AtomicFile
import com.mpkg.app.util.JsonParser
import com.mpkg.app.util.JsonValue
import com.mpkg.app.util.JsonWriter
import com.mpkg.app.util.asBoolean
import com.mpkg.app.util.asInt
import com.mpkg.app.util.asLong
import com.mpkg.app.util.asString
import com.mpkg.app.util.asStringOrNull
import com.mpkg.app.util.get
import java.io.File

/**
 * 设置的持久化（任务卡 T3.4）：JSON 存平台配置目录，原子写入，不引三方库。
 *
 * 任何读取失败都回退到默认值，绝不让设置文件损坏导致应用起不来。
 */
class SettingsStore(private val file: File = PlatformPaths.settingsFile()) {

    fun load(): AppSettings {
        val text = AtomicFile.readTextOrNull(file) ?: return AppSettings()
        val root = JsonParser.parseOrNull(text) ?: return AppSettings()
        return try {
            fromJson(root).normalized()
        } catch (e: Exception) {
            AppLog.w("设置文件解析失败，使用默认值: ${e.message}")
            AppSettings()
        }
    }

    fun save(settings: AppSettings) {
        try {
            AtomicFile.writeText(file, JsonWriter.write(toJson(settings.normalized())))
        } catch (e: Exception) {
            AppLog.e("保存设置失败: ${e.message}", e)
        }
    }

    private fun toJson(s: AppSettings): JsonValue = JsonValue.obj(
        "version" to JsonValue.of(1),
        "outputDir" to JsonValue.of(s.outputDir),
        "overwritePolicy" to JsonValue.of(s.overwritePolicy.name),
        "concurrency" to JsonValue.of(s.concurrency),
        "exportPreview" to JsonValue.of(s.exportPreview),
        "decodeMode" to JsonValue.of(s.decodeMode.name),
        "themeMode" to JsonValue.of(s.themeMode.name),
        "accent" to JsonValue.of(s.accent.name),
        "reduceMotion" to JsonValue.of(s.reduceMotion),
        "openOutputWhenDone" to JsonValue.of(s.openOutputWhenDone),
        "autoCheckUpdate" to JsonValue.of(s.autoCheckUpdate),
        "updateCheckUrl" to JsonValue.of(s.updateCheckUrl),
        "updateDownloadUrl" to JsonValue.of(s.updateDownloadUrl),
        "lastUpdateCheckAt" to JsonValue.of(s.lastUpdateCheckAt),
        "ignoredUpdateVersion" to JsonValue.of(s.ignoredUpdateVersion),
        "window" to JsonValue.obj(
            "x" to JsonValue.of(s.window.x),
            "y" to JsonValue.of(s.window.y),
            "width" to JsonValue.of(s.window.width),
            "height" to JsonValue.of(s.window.height),
            "maximized" to JsonValue.of(s.window.maximized)
        )
    )

    private fun fromJson(root: JsonValue): AppSettings {
        val window = root["window"]
        return AppSettings(
            outputDir = root["outputDir"].asString(AppSettings().outputDir),
            overwritePolicy = runCatching {
                OverwritePolicy.valueOf(root["overwritePolicy"].asString(OverwritePolicy.RENAME.name))
            }.getOrDefault(OverwritePolicy.RENAME),
            concurrency = root["concurrency"].asInt(AppSettings.DEFAULT_CONCURRENCY),
            exportPreview = root["exportPreview"].asBoolean(false),
            decodeMode = DecodeMode.fromName(root["decodeMode"].asStringOrNull()),
            themeMode = ThemeMode.fromName(root["themeMode"].asStringOrNull()),
            accent = runCatching {
                AccentPreset.valueOf(root["accent"].asString(AccentPreset.DEFAULT.name))
            }.getOrDefault(AccentPreset.DEFAULT),
            reduceMotion = root["reduceMotion"].asBoolean(false),
            openOutputWhenDone = root["openOutputWhenDone"].asBoolean(false),
            autoCheckUpdate = root["autoCheckUpdate"].asBoolean(true),
            updateCheckUrl = root["updateCheckUrl"].asString(AppSettings.DEFAULT_UPDATE_CHECK_URL),
            updateDownloadUrl = root["updateDownloadUrl"].asString(""),
            lastUpdateCheckAt = root["lastUpdateCheckAt"].asLong(0L),
            ignoredUpdateVersion = root["ignoredUpdateVersion"].asStringOrNull(),
            window = WindowState(
                x = window["x"].asInt(WindowState.UNSET),
                y = window["y"].asInt(WindowState.UNSET),
                width = window["width"].asInt(WindowState.DEFAULT_WIDTH),
                height = window["height"].asInt(WindowState.DEFAULT_HEIGHT),
                maximized = window["maximized"].asBoolean(false)
            )
        )
    }
}
