package com.nxssie.acpssh.desktop

import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.nxssie.acpssh.App
import com.nxssie.acpssh.DesktopAcpHost
import com.nxssie.acpssh.DesktopSshTerminalHost
import com.nxssie.acpssh.SshConnectionConfig
import com.nxssie.acpssh.SshSession
import com.nxssie.acpssh.acp.AcpExecTransport
import com.nxssie.acpssh.acp.ContentBlock
import com.nxssie.acpssh.acp.DuplexRawByteChannel
import com.nxssie.acpssh.acp.NdjsonFramer
import com.nxssie.acpssh.acp.PermissionOutcome
import com.nxssie.acpssh.acp.PermissionRequest
import com.nxssie.acpssh.acp.RawByteChannel
import com.nxssie.acpssh.acp.RemoteAcpProcess
import com.nxssie.acpssh.acp.SessionUpdate
import com.nxssie.acpssh.acp.ToolCallContent
import com.nxssie.acpssh.acp.asRawByteChannel
import com.nxssie.acpssh.jvm.SshjConnect
import com.nxssie.acpssh.session.AcpSession
import com.nxssie.acpssh.session.TerminalConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.readString
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "--test-ssh" -> exitProcess(runSshValidation(args.drop(1)))
        "--test-acp-persist" -> exitProcess(runAcpPersistValidation(args.drop(1)))
        "--test-acp-client" -> exitProcess(runAcpClientValidation(args.drop(1)))
    }
    application {
        Window(onCloseRequest = ::exitApplication, title = "ACP Terminal") {
            val terminalHost = remember { DesktopSshTerminalHost() }
            val acpHost = remember { DesktopAcpHost() }
            App(terminalHost, acpHost)
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

/**
 * Validación de Fase C/D: flujo completo del cliente ACP contra el sshd de
 * prueba, con un AGENTE FAKE que habla NDJSON (el binario real no está en el
 * servidor). Cubre: arranque persistente, initialize, session/new, prompt con
 * streaming (texto + tool call + diff + plan) y session/request_permission
 * (el agente espera la respuesta del cliente antes de cerrar el turno).
 *
 * Uso: --test-acp-client --host H [--port P] --user U --key K --known-hosts KH [--run-dir DIR]
 */
private fun runAcpClientValidation(args: List<String>): Int {
    val opts = parseArgs(args)
    if (opts == null) {
        println("Uso: --test-acp-client --host H [--port P] --user U --key K --known-hosts KH [--run-dir DIR]")
        return 2
    }
    val runDir = opts["run-dir"] ?: "/tmp/acp-ssh-kmp-client-validate"
    val config = TerminalConfig(
        host = opts.getValue("host"),
        port = opts["port"]?.toIntOrNull() ?: 22,
        username = opts.getValue("user"),
        privateKeyPem = File(opts.getValue("key")).readText(),
        remoteCommand = FAKE_ACP_AGENT,
        acpRunDir = runDir,
    )

    return runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val knownHosts = File(opts.getValue("known-hosts"))
            val ssh = SshjConnect.connect(config.toSshConfig(), knownHosts)
            val transport = object : AcpExecTransport {
                override suspend fun exec(command: String): RawByteChannel =
                    ssh.exec(command).asRawByteChannel()

                override fun close() = ssh.close()
            }

            val acpSession = AcpSession(transport, config, scope)
            val result = acpSession.start()
            val client = result.client
            println("initialize OK: ${result.initialize.agentName} v${result.initialize.agentVersion}")
            println("session/new OK: ${result.newSession.sessionId}")

            val updates = mutableListOf<SessionUpdate>()
            val permission = CompletableDeferred<PermissionRequest>()
            val updateJob = launch { for (u in client.updates) updates.add(u) }
            val permissionJob = launch { permission.complete(client.permissionRequests.receive()) }

            val promptDeferred = async { withTimeout(15_000) { client.prompt(result.newSession.sessionId, "hola") } }
            val perm = withTimeout(15_000) { permission.await() }
            println("request_permission OK: opciones=${perm.options.map { it.optionId }}")
            check(perm.options.size == 2) { "esperaba 2 opciones de permiso" }

            client.respondPermission(perm, PermissionOutcome.Selected("allow"))
            val promptResult = withTimeout(15_000) { promptDeferred.await() }
            check(promptResult.endTurn) { "esperaba stopReason end_turn" }
            updateJob.cancel()
            permissionJob.cancel()

            val text = updates.filterIsInstance<SessionUpdate.AgentMessageChunk>()
                .map { (it.chunk.content as ContentBlock.Text).text }
                .joinToString("")
            check(text.contains("Hola desde el agente fake")) { "no llegó el texto del agente: $text" }
            val tool = updates.filterIsInstance<SessionUpdate.ToolCall>().single()
            check(tool.toolCall.toolCallId == "tc1") { "tool call inesperada" }
            val diff = updates.filterIsInstance<SessionUpdate.ToolCallUpdate>()
                .flatMap { it.toolCallUpdate.content }
                .filterIsInstance<ToolCallContent.Diff>()
                .single()
            check(diff.path == "/tmp/fake.txt") { "diff inesperado: ${diff.path}" }
            val plan = updates.filterIsInstance<SessionUpdate.Plan>().single()
            check(plan.plan.entries.size == 2) { "plan inesperado" }

            println("prompt OK: ${updates.size} updates (texto + tool call + diff + plan), stopReason=end_turn")
            println("PASS")
            0
        } catch (e: Exception) {
            println("FAIL: ${e.message}")
            1
        } finally {
            scope.cancel()
            runCatching {
                val cleanup = SshjConnect.connect(config.toSshConfig(), File(opts.getValue("known-hosts")))
                val pidPath = "$runDir/${RemoteAcpProcess.PID_FILE}"
                val cmd = cleanup.exec(
                    "kill \$(cat ${RemoteAcpProcess.shellQuote(pidPath)}) 2>/dev/null; " +
                        "rm -rf ${RemoteAcpProcess.shellQuote(runDir)}",
                )
                try {
                    val stderrDeferred = async(Dispatchers.IO) { cmd.stderr.readString() }
                    cmd.stdout.readString()
                    cmd.exitStatus()
                    stderrDeferred.await()
                } finally {
                    cmd.close()
                    cleanup.close()
                }
            }
        }
    }
}

/**
 * Agente ACP fake para la validación: habla NDJSON y emula el ciclo de vida
 * (initialize → session/new → prompt con updates y request_permission). El id
 * de las respuestas es fijo porque el cliente manda initialize=1, session/new=2,
 * prompt=3 en una instancia nueva. Se pasa como comando de arranque remoto.
 */
private const val FAKE_ACP_AGENT: String = """while IFS= read -r line; do
  if printf '%s' "${'$'}line" | grep -q '"method":"initialize"'; then
    echo '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"agentCapabilities":{},"agentInfo":{"name":"fake-acp-agent","version":"0.1.0"},"authMethods":[]}}'
  elif printf '%s' "${'$'}line" | grep -q '"method":"session/new"'; then
    echo '{"jsonrpc":"2.0","id":2,"result":{"sessionId":"fake-session-1"}}'
    echo '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"fake-session-1","update":{"sessionUpdate":"available_commands_update","availableCommands":[{"name":"x","description":"y"}]}}}'
  elif printf '%s' "${'$'}line" | grep -q '"method":"session/prompt"'; then
    echo '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"fake-session-1","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Hola desde el agente fake"},"messageId":"m1"}}}'
    echo '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"fake-session-1","update":{"sessionUpdate":"tool_call","toolCall":{"toolCallId":"tc1","title":"Herramienta de prueba","kind":"execute","status":"in_progress"}}}}'
    echo '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"fake-session-1","update":{"sessionUpdate":"tool_call_update","toolCallUpdate":{"toolCallId":"tc1","status":"completed","content":[{"type":"diff","path":"/tmp/fake.txt","oldText":"hola","newText":"adios"}]}}}}'
    echo '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"fake-session-1","update":{"sessionUpdate":"plan","plan":{"entries":[{"content":"Paso 1","priority":"high","status":"completed"},{"content":"Paso 2","priority":"medium","status":"pending"}]}}}}'
    echo '{"jsonrpc":"2.0","id":4,"method":"session/request_permission","params":{"sessionId":"fake-session-1","toolCall":{"toolCallId":"tc2","title":"Necesito permiso","status":"in_progress"},"options":[{"optionId":"allow","name":"Permitir una vez","kind":"allow_once"},{"optionId":"reject","name":"Rechazar","kind":"reject_once"}]}}'
  elif printf '%s' "${'$'}line" | grep -q '"outcome"'; then
    echo '{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}'
  fi
done"""

private fun TerminalConfig.toSshConfig() = SshConnectionConfig(
    host = host,
    port = port,
    username = username,
    auth = SshConnectionConfig.Auth.KeyData(privateKeyPem),
)

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
