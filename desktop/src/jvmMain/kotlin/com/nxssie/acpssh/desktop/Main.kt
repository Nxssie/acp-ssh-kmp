package com.nxssie.acpssh.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.nxssie.acpssh.App
import com.nxssie.acpssh.DesktopSshTerminalHost
import com.nxssie.acpssh.SshConnectionConfig
import com.nxssie.acpssh.jvm.SshjConnect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.io.readString
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--test-ssh") {
        exitProcess(runSshValidation(args.drop(1)))
    }
    application {
        Window(onCloseRequest = ::exitApplication, title = "ACP Terminal") {
            val host = remember { DesktopSshTerminalHost() }
            App(host)
        }
    }
}

/**
 * Validación de Fase 1: conecta por SSH, ejecuta un comando remoto simple y
 * confirma que stdout se lee por streams (sin ACP todavía).
 *
 * Uso: --test-ssh --host H [--port P] --user U --key K --known-hosts KH [--command "echo hola"]
 */
private fun runSshValidation(args: List<String>): Int {
    val opts = parseArgs(args)
    if (opts == null) {
        println("Uso: --test-ssh --host H [--port P] --user U --key K --known-hosts KH [--command CMD]")
        return 2
    }
    val command = opts["command"] ?: "echo hola"
    val config = SshConnectionConfig(
        host = opts.getValue("host"),
        port = opts["port"]?.toIntOrNull() ?: 22,
        username = opts.getValue("user"),
        auth = SshConnectionConfig.Auth.KeyFile(opts.getValue("key")),
    )

    return runBlocking {
        val session = SshjConnect.connect(config, File(opts.getValue("known-hosts")))
        val channel = try {
            session.exec(command)
        } catch (e: Exception) {
            session.close()
            throw e
        }
        try {
            val stderrDeferred = async(Dispatchers.IO) { channel.stderr.readString() }
            val stdout = channel.stdout.readString()
            val status = channel.exitStatus()
            val stderr = stderrDeferred.await()

            println("exit=$status")
            println("stdout=$stdout")
            if (stderr.isNotBlank()) println("stderr=$stderr")

            if (status == 0 && stdout.isNotBlank()) 0 else 1
        } finally {
            channel.close()
            session.close()
        }
    }
}

private fun parseArgs(args: List<String>): Map<String, String>? {
    val opts = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i]
        if (!key.startsWith("--") || i + 1 >= args.size) return null
        opts[key.removePrefix("--")] = args[i + 1]
        i += 2
    }
    return opts.takeIf { "host" in it && "user" in it && "key" in it && "known-hosts" in it }
}
