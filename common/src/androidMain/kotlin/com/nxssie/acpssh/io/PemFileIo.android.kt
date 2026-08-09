package com.nxssie.acpssh.io

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberPemExporter(): (String, String) -> Unit {
    val context = LocalContext.current
    var pendingContent by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-pem-file"),
    ) { uri ->
        val content = pendingContent
        pendingContent = null
        if (uri != null && content != null) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(content.encodeToByteArray())
            }
        }
    }
    return { suggestedFileName, content ->
        pendingContent = content
        launcher.launch(suggestedFileName)
    }
}

@Composable
actual fun rememberPemImporter(onImported: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
            if (text != null) onImported(text)
        }
    }
    return { launcher.launch(arrayOf("*/*")) }
}
