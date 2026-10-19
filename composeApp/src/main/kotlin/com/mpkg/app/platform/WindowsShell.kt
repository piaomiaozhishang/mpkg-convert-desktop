package com.mpkg.app.platform

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.Window
import java.io.File
import java.util.concurrent.FutureTask

/** Modern Windows Shell APIs. COM objects stay on their owning STA and are always released. */
internal object WindowsShell {
    private interface ShellApi : StdCallLibrary {
        fun SHParseDisplayName(name: WString, context: Pointer?, pidl: PointerByReference,
                               attributes: Int, result: Pointer?): Int
        fun SHOpenFolderAndSelectItems(pidl: Pointer, count: Int, children: Pointer?, flags: Int): Int
        fun SHCreateItemFromParsingName(name: WString, context: Pointer?, iid: GUID,
                                        item: PointerByReference): Int
    }
    private val shell by lazy { Native.load("shell32", ShellApi::class.java) }
    private class Com(pointer: Pointer) : Unknown(pointer), AutoCloseable {
        fun call(slot: Int, vararg args: Any?): Int = _invokeNativeInt(slot, arrayOf(pointer, *args))
        fun checked(slot: Int, vararg args: Any?) { checkHr(call(slot, *args)) }
        override fun close() { Release() }
    }
    private fun checkHr(hr: Int) {
        check(hr >= 0) { "Windows Shell 调用失败 (0x${Integer.toHexString(hr)})" }
    }

    /** Nested AWT event loop keeps repaint/dispatch alive while the native modal window owns input. */
    private fun <T> onSta(action: () -> T): T {
        val loop = if (EventQueue.isDispatchThread()) Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop() else null
        val task = FutureTask {
            val ole = Ole32.INSTANCE
            checkHr(ole.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED or Ole32.COINIT_DISABLE_OLE1DDE).toInt())
            try { action() } finally { ole.CoUninitialize() }
        }
        Thread({
            try { task.run() } finally { if (loop != null) EventQueue.invokeLater { loop.exit() } }
        }, "mpkg-windows-shell").apply { isDaemon = true; start() }
        loop?.enter()
        return task.get()
    }

    fun reveal(file: File): Boolean = onSta {
        val pidl = PointerByReference()
        checkHr(shell.SHParseDisplayName(WString(file.absolutePath), null, pidl, 0, null))
        try { checkHr(shell.SHOpenFolderAndSelectItems(pidl.value, 0, null, 0)); true }
        finally { Ole32.INSTANCE.CoTaskMemFree(pidl.value) }
    }

    fun choose(parent: Window?, folders: Boolean, current: File? = null): List<File> {
        val owner = parent?.takeIf { it.isDisplayable }?.let(Native::getWindowPointer)
        return onSta {
            val result = PointerByReference()
            checkHr(Ole32.INSTANCE.CoCreateInstance(
                GUID("{DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7}"), null, 1,
                GUID("{D57C7288-D4AD-4768-BE02-9D969532D960}"), result).toInt())
            Com(result.value).use { dialog ->
                val flags = IntByReference()
                dialog.checked(10, flags) // IFileDialog::GetOptions
                dialog.checked(9, flags.value or 0x8 or 0x40 or 0x800 or
                    if (folders) 0x20 else (0x200 or 0x1000)) // filesystem, path exists, folders / multi+file exists
                dialog.checked(17, WString(if (folders) "选择输出目录" else "选择壁纸包（可多选）"))
                // COMDLG_FILTERSPEC is a pair of UTF-16 pointers. Keep their memory alive through Show.
                val filterName = wide("壁纸包 (*.mpkg;*.pkg)")
                val filterPattern = wide("*.mpkg;*.pkg")
                val filter = Memory((Native.POINTER_SIZE * 2).toLong()).apply {
                    setPointer(0, filterName); setPointer(Native.POINTER_SIZE.toLong(), filterPattern)
                }
                if (!folders) dialog.checked(4, 1, filter)
                current?.absoluteFile?.let { if (it.isDirectory) it else it.parentFile }
                    ?.takeIf { it.isDirectory }?.let { directory ->
                        val item = PointerByReference()
                        checkHr(shell.SHCreateItemFromParsingName(WString(directory.path), null,
                            GUID("{43826D1E-E718-42EE-BC55-A1E261C37BFE}"), item))
                        Com(item.value).use { dialog.checked(12, it.pointer) }
                    }
                val hr = dialog.call(3, owner) // IModalWindow::Show
                java.lang.ref.Reference.reachabilityFence(filterName)
                java.lang.ref.Reference.reachabilityFence(filterPattern)
                java.lang.ref.Reference.reachabilityFence(filter)
                if (hr == 0x800704C7.toInt()) return@use emptyList() // Cancel is not a failure.
                checkHr(hr)
                val array = PointerByReference()
                dialog.checked(27, array) // IFileOpenDialog::GetResults
                Com(array.value).use { items ->
                    val count = IntByReference()
                    items.checked(7, count)
                    List(count.value) { index ->
                        val item = PointerByReference()
                        items.checked(8, index, item)
                        Com(item.value).use { entry ->
                            val path = PointerByReference()
                            entry.checked(5, 0x80058000.toInt(), path) // SIGDN_FILESYSPATH
                            try { File(path.value.getWideString(0)) }
                            finally { Ole32.INSTANCE.CoTaskMemFree(path.value) }
                        }
                    }
                }
            }
        }
    }

    private fun wide(value: String) = Memory((value.length + 1L) * Native.WCHAR_SIZE).apply { setWideString(0, value) }
}
