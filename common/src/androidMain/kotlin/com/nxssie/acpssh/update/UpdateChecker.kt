package com.nxssie.acpssh.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Autoupdate sin Play Store (Fase K): la app se distribuye solo por GitHub
 * Releases (ver .github/workflows/android-build.yml), así que "buscar
 * actualizaciones" es leer el último release publicado y comparar su
 * versionCode con el instalado — no hay servidor propio ni Play Console.
 */
object UpdateChecker {

    // "/releases/latest" ignora los prerelease (todos los builds de CI lo son
    // — ver android-build.yml) y devuelve 404 siempre: el autoupdate nunca
    // encontraba nada. La lista completa viene ordenada por fecha de
    // creación descendente, así que el primer elemento es el build real más
    // reciente, sea o no prerelease.
    private const val RELEASES_API = "https://api.github.com/repos/Nxssie/acp-ssh-kmp/releases?per_page=1"

    /** El tag de release siempre es "v<versionName>-<versionCode>-<shortSha>" (ver CI). */
    private val TAG_VERSION_CODE = Regex("""^v[\d.]+-(\d+)-[0-9a-f]+$""")

    data class LatestRelease(val tag: String, val versionCode: Long, val apkUrl: String, val apkName: String)

    /** Devuelve el último release si es más nuevo que [currentVersionCode]; null si no hay nada que actualizar o falla la consulta. */
    suspend fun checkForUpdate(currentVersionCode: Long): LatestRelease? = withContext(Dispatchers.IO) {
        runCatching {
            val body = httpGetText(RELEASES_API)
            val root = Json.parseToJsonElement(body).jsonArray.firstOrNull()?.jsonObject
                ?: return@runCatching null
            val tag = root["tag_name"]?.jsonPrimitive?.content ?: return@runCatching null
            val versionCode = TAG_VERSION_CODE.matchEntire(tag)?.groupValues?.get(1)?.toLongOrNull()
                ?: return@runCatching null
            if (versionCode <= currentVersionCode) return@runCatching null
            val apkAsset = root["assets"]?.jsonArray
                ?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
                ?: return@runCatching null
            LatestRelease(
                tag = tag,
                versionCode = versionCode,
                apkUrl = apkAsset["browser_download_url"]?.jsonPrimitive?.content ?: return@runCatching null,
                apkName = apkAsset["name"]?.jsonPrimitive?.content ?: return@runCatching null,
            )
        }.getOrNull()
    }

    /** Descarga el APK a cacheDir, reportando progreso 0f..1f (o -1f si el servidor no manda Content-Length). */
    suspend fun downloadApk(
        context: Context,
        release: LatestRelease,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val outFile = File(context.cacheDir, release.apkName)
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        connection.connect()
        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
            outFile.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var readSoFar = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    readSoFar += read
                    onProgress(if (total > 0) readSoFar.toFloat() / total else -1f)
                }
            }
        }
        outFile
    }

    /** Lanza el instalador del sistema sobre el APK descargado (pide confirmación explícita al usuario). */
    fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun httpGetText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        connection.connect()
        return connection.inputStream.bufferedReader().use { it.readText() }
    }
}
