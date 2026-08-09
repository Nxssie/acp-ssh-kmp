package com.nxssie.acpssh.io

import androidx.compose.runtime.Composable
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
actual fun rememberPemExporter(): (String, String) -> Unit = { suggestedFileName, content ->
    val chooser = JFileChooser().apply {
        selectedFile = File(suggestedFileName)
        fileFilter = FileNameExtensionFilter("Clave privada PEM", "pem")
    }
    if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile.writeText(content)
    }
}

@Composable
actual fun rememberPemImporter(onImported: (String) -> Unit): () -> Unit = {
    val chooser = JFileChooser().apply {
        fileFilter = FileNameExtensionFilter("Clave privada PEM", "pem")
    }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        onImported(chooser.selectedFile.readText())
    }
}
