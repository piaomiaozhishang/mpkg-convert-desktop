package com.mpkg.app.platform

import com.mpkg.app.util.AppLog
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.io.File

/**
 * 窗口级拖拽（任务卡 T2.2）。
 *
 * 用 AWT `DropTarget` 挂在窗口上：它是三平台都稳定可用的底层通道，
 * 不依赖 Compose 的拖拽 API，因此天然充当兜底路径；
 * 高亮反馈由 Compose 层订阅回调驱动（[onDragActive]）。
 *
 * 只接收文件路径，不做任何解析——解析统一交给 T2.4 的队列。
 */
class WindowDropTarget(
    private val component: java.awt.Component,
    private val onDragActive: (Boolean) -> Unit,
    private val onFilesDropped: (List<File>) -> Unit
) {

    private var dropTarget: DropTarget? = null
    private var listener: DropTargetAdapter? = null

    fun install() {
        if (dropTarget != null) return
        try {
            val adapter = object : DropTargetAdapter() {
                override fun dragEnter(dtde: DropTargetDragEvent) {
                    if (isFileDrag(dtde)) {
                        dtde.acceptDrag(DnDConstants.ACTION_COPY)
                        onDragActive(true)
                    } else {
                        dtde.rejectDrag()
                    }
                }

                override fun dragOver(dtde: DropTargetDragEvent) {
                    if (isFileDrag(dtde)) dtde.acceptDrag(DnDConstants.ACTION_COPY) else dtde.rejectDrag()
                }

                override fun dragExit(dte: DropTargetEvent) {
                    onDragActive(false)
                }

                override fun drop(dtde: DropTargetDropEvent) {
                    onDragActive(false)
                    try {
                        dtde.acceptDrop(DnDConstants.ACTION_COPY)
                        val transferable = dtde.transferable
                        val files = readFiles(transferable)
                        dtde.dropComplete(files.isNotEmpty())
                        if (files.isNotEmpty()) onFilesDropped(files)
                    } catch (e: Exception) {
                        AppLog.w("拖拽处理失败: ${e.message}")
                        dtde.dropComplete(false)
                    }
                }
            }
            listener = adapter
            dropTarget = DropTarget(component, DnDConstants.ACTION_COPY, adapter, true)
            AppLog.i("窗口拖拽已启用（AWT DropTarget）")
        } catch (e: Exception) {
            AppLog.e("启用窗口拖拽失败", e)
        }
    }

    fun uninstall() {
        val target = dropTarget ?: return
        listener?.let(target::removeDropTargetListener)
        listener = null
        dropTarget = null
    }

    private fun isFileDrag(event: DropTargetDragEvent): Boolean =
        event.transferable?.isDataFlavorSupported(DataFlavor.javaFileListFlavor) == true

    @Suppress("UNCHECKED_CAST")
    private fun readFiles(transferable: java.awt.datatransfer.Transferable): List<File> {
        if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return emptyList()
        val data = transferable.getTransferData(DataFlavor.javaFileListFlavor)
        return (data as? List<File>).orEmpty().filter { it.isFile }
    }
}
