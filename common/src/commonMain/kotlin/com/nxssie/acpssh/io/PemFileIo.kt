package com.nxssie.acpssh.io

import androidx.compose.runtime.Composable

/**
 * Exportador de archivo `.pem`: devuelve una función que, al invocarla, abre el
 * diálogo nativo "Guardar como" y escribe `content` en el archivo elegido.
 */
@Composable
expect fun rememberPemExporter(): (suggestedFileName: String, content: String) -> Unit

/**
 * Importador de archivo `.pem`: devuelve una función que, al invocarla, abre el
 * diálogo nativo "Abrir archivo"; si el usuario elige uno, llama a [onImported]
 * con su contenido como texto.
 */
@Composable
expect fun rememberPemImporter(onImported: (String) -> Unit): () -> Unit
