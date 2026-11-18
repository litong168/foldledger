package com.foldledger.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import com.foldledger.ui.l10n.t

/** 桌面文件选择：JFileChooser，字节读出后交共享 VM。 */
@Composable
actual fun rememberBillFilePicker(onPicked: (name: String, bytes: ByteArray) -> Unit): () -> Unit =
    remember {
        val open: () -> Unit = {
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.FILES_ONLY
                fileFilter = FileNameExtensionFilter(t("账单文件 (xlsx / csv)"), "xlsx", "csv")
                isAcceptAllFileFilterUsed = true
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                val file = chooser.selectedFile
                if (file != null && file.isFile) {
                    onPicked(file.name, file.readBytes())
                }
            }
        }
        open
    }
