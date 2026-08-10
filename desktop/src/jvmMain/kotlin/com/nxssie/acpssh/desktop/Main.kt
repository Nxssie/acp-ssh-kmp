package com.nxssie.acpssh.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.nxssie.acpssh.App
import com.nxssie.acpssh.DesktopSshTerminalHost
import com.nxssie.acpssh.SshConnectionConfig
import com.nxssie.acpssh.SshSession
import com.nxssie.acpssh.acp.DuplexRawByteChannel
import com.nxssie.acpssh.acp.NdjsonFramer
import com.nxssie.acpssh.acp.RemoteAcpProcess
import com.nxssie.acpssh.acp.asRawByteChannel
import com.nxssie.acpssh.jvm.SshjConnect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.readString
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "--test-ssh" -> exitProcess(runSshValidation(args.drop(1)))
        "--test-acp-persist" -> exitProcess(runAcpPersistValidation(args.drop(1)))
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

/**
 * Validación de Fase B: arranca el agente remoto vía FIFOs + `setsid`
 * (desacoplado de la sesión SSH que lo lanza), hace un round-trip NDJSON,
 * simula una desconexión completa (cierra ambos canales `exec` sin tocar el
 * proceso remoto) y confirma al reconectar que el proceso sigue vivo y no se
 * perdió ningún mensaje.
 *
 * El "agente" es un bucle de shell que hace eco de cada línea con un prefijo
 * (`claude-code-acp` real no está instalado en el sshd de prueba): lo que se
 * valida aquí es la mecánica de persistencia (Fase B), no el protocolo ACP
 * (Fase C).
 *
 * Uso: --test-acp-persist --host H [--port P] --user U --key K --known-hosts KH [--run-dir DIR]
 */
private fun runAcpPersistValidation(args: List<String>): Int {
    val opts = parseArgs(args)
    if (opts == null) {
        println(
            "Uso: --test-acp-persist --host H [--port P] --user U --key K --known-hosts KH [--run-dir DIR]",
        )
        return 2
    }
    val runDir = opts["run-dir"] ?: "/tmp/acp-ssh-kmp-validate"
    val agentCommand = "while IFS= read -r line; do printf 'echo:%s\\n' \"\$line\"; done"
    val config = SshConnectionConfig(
        host = opts.getValue("host"),
        port = opts["port"]?.toIntOrNull() ?: 22,
        username = opts.getValue("user"),
        auth = SshConnectionConfig.Auth.KeyFile(opts.getValue("key")),
    )

    return runBlocking {
        val session = SshjConnect.connect(config, File(opts.getValue("known-hosts")))
        try {
            val launchChannel = session.exec(RemoteAcpProcess.launchScript(runDir, agentCommand))
            val launchOutput = try {
                val stderrDeferred = async(Dispatchers.IO) { launchChannel.stderr.readString() }
                val stdout = launchChannel.stdout.readString().trim()
                launchChannel.exitStatus()
                stderrDeferred.await()
                stdout
            } finally {
                launchChannel.close()
            }
            println("launch=$launchOutput")
            if (launchOutput != "STARTED" && launchOutput != "ALREADY_RUNNING") {
                println("FAIL: respuesta de arranque inesperada")
                return@runBlocking 1
            }

            acpRoundTrip(session, runDir, "hello1", "echo:hello1")
            println("round-trip 1 OK (antes de desconectar)")

            acpRoundTrip(session, runDir, "hello2", "echo:hello2")
            println("round-trip 2 OK (tras cerrar y reabrir los canales exec: el proceso remoto sobrevivió)")

            println("PASS")
            0
        } catch (e: Exception) {
            println("FAIL: ${e.message}")
            1
        } finally {
            runCatching {
                val pidPath = "$runDir/${RemoteAcpProcess.PID_FILE}"
                val cleanup = session.exec(
                    "kill \$(cat ${RemoteAcpProcess.shellQuote(pidPath)}) 2>/dev/null; " +
                        "rm -rf ${RemoteAcpProcess.shellQuote(runDir)}",
                )
                try {
                    val stderrDeferred = async(Dispatchers.IO) { cleanup.stderr.readString() }
                    cleanup.stdout.readString()
                    cleanup.exitStatus()
                    stderrDeferred.await()
                } finally {
                    cleanup.close()
                }
            }
            session.close()
        }
    }
}

/** Abre un canal `exec` de lectura y otro de escritura, manda [send] y espera [expect] por NDJSON. */
private suspend fun acpRoundTrip(session: SshSession, runDir: String, send: String, expect: String) {
    val reader = session.exec(RemoteAcpProcess.readerCommand(runDir)).asRawByteChannel()
    val writer = session.exec(RemoteAcpProcess.writerCommand(runDir)).asRawByteChannel()
    val duplex = DuplexRawByteChannel(reader, writer)
    try {
        val framer = NdjsonFramer(duplex)
        framer.writeLine(send)
        val received = withTimeout(5_000L) { framer.lines().first() }
        check(received == expect) { "esperaba '$expect', llegó '$received'" }
    } finally {
        duplex.close()
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
