package com.nxssie.acpssh.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.InterruptedIOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * El cliente completo sobre un canal fake: handshake, prompt con actualizaciones
 * intercaladas, permiso entrante, respuestas desordenadas y requests de métodos
 * desconocidos. Valida el wire protocol contra las formas reales capturadas del
 * adaptador `claude-agent-acp` (initialize / session/new / session/update /
 * session/request_permission).
 *
 * El canal fake es una cola que el test rellena en el momento exacto en que
 * quiere que el agente "responda": modela el orden real del protocolo (el
 * agente nunca responde antes de recibir el request) y permite probar llegadas
 * desordenadas sin carreras.
 */
class AcpClientTest {

    private class QueueChannel : RawByteChannel {
        private val queue = LinkedBlockingQueue<String>()
        val written = StringBuilder()

        fun send(line: String) {
            // El protocolo real termina cada mensaje con \n; sin él el framer
            // bufferiza la línea y solo la emite al llegar EOF (matando al reader).
            queue.add(line + "\n")
        }

        override suspend fun readChunk(buffer: ByteArray): Int {
            val line = queue.poll(2, TimeUnit.SECONDS) ?: return -1
            val bytes = line.encodeToByteArray()
            val n = minOf(bytes.size, buffer.size)
            bytes.copyInto(buffer, 0, 0, n)
            return n
        }

        override suspend fun write(bytes: ByteArray) {
            written.append(bytes.decodeToString())
        }

        override suspend fun flush() {}

        override fun close() {}
    }

    private val initializeResponse =
        """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"agentCapabilities":{"loadSession":true},"agentInfo":{"name":"claude-agent-acp","title":"Claude Agent","version":"0.66.0"},"authMethods":[]}}"""
    private val newSessionResponse = """{"jsonrpc":"2.0","id":2,"result":{"sessionId":"sess-1","modes":{"currentModeId":"default"},"configOptions":[]}}"""
    private val promptResponse = """{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}"""

    private fun client(channel: QueueChannel): Pair<AcpClient, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = AcpClient(NdjsonFramer(channel), scope)
        client.start()
        return client to scope
    }

    @Test
    fun fullHandshakeInitializeNewSession() = runBlocking {
        val channel = QueueChannel()
        val (client, scope) = client(channel)
        try {
            val initDeferred = async { withTimeout(5_000) { client.initialize() } }
            // El agente solo responde tras recibir el request (como en el protocolo real).
            while (!channel.written.contains("\"method\":\"initialize\"")) kotlinx.coroutines.delay(5)
            channel.send(initializeResponse)
            val init = initDeferred.await()
            assertEquals(1, init.protocolVersion)
            assertEquals("claude-agent-acp", init.agentName)
            assertEquals("0.66.0", init.agentVersion)

            val sessionDeferred = async { withTimeout(5_000) { client.newSession("/tmp") } }
            while (!channel.written.contains("\"method\":\"session/new\"")) kotlinx.coroutines.delay(5)
            channel.send(newSessionResponse)
            assertEquals("sess-1", sessionDeferred.await().sessionId)

            assertTrue(channel.written.contains("\"protocolVersion\":1"))
            assertTrue(channel.written.contains("\"cwd\":\"/tmp\""))
            assertTrue(channel.written.contains("\"clientInfo\":{\"name\":\"acp-ssh-kmp"))
        } finally {
            client.close()
            scope.cancel()
        }
    }

    @Test
    fun promptStreamsUpdatesUntilEndTurn() = runBlocking {
        val channel = QueueChannel()
        val (client, scope) = client(channel)
        try {
            val updates = mutableListOf<SessionUpdate>()
            val collector = launch { for (u in client.updates) updates.add(u) }

            val initDeferred = async { withTimeout(5_000) { client.initialize() } }
            while (!channel.written.contains("initialize")) kotlinx.coroutines.delay(5)
            channel.send(initializeResponse)
            initDeferred.await()

            // session/new y prompt en paralelo; el agente responde el prompt
            // ANTES que el session/new (desordenado) — el cliente casa por id.
            val newSessionDeferred = async { withTimeout(5_000) { client.newSession("/tmp") } }
            val promptDeferred = async { withTimeout(5_000) { client.prompt("sess-1", "hola") } }
            while (!channel.written.contains("session/prompt")) kotlinx.coroutines.delay(5)

            channel.send(
                """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"sess-1","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Hola"},"messageId":"m1"}}}""",
            )
            channel.send(
                """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"sess-1","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":" mundo"},"messageId":"m1"}}}""",
            )
            channel.send(
                """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"sess-1","update":{"sessionUpdate":"tool_call","toolCall":{"toolCallId":"tc1","title":"Buscar","kind":"search","status":"in_progress"}}}}""",
            )
            channel.send(promptResponse) // llega antes que newSessionResponse
            channel.send(newSessionResponse)

            val result = promptDeferred.await()
            assertEquals("end_turn", result.stopReason)
            assertEquals("sess-1", newSessionDeferred.await().sessionId)
            collector.cancel()

            assertEquals(3, updates.size)
            val chunk1 = updates[0] as SessionUpdate.AgentMessageChunk
            val chunk2 = updates[1] as SessionUpdate.AgentMessageChunk
            assertEquals("Hola", (chunk1.chunk.content as ContentBlock.Text).text)
            assertEquals(" mundo", (chunk2.chunk.content as ContentBlock.Text).text)
            val tool = updates[2] as SessionUpdate.ToolCall
            assertEquals("tc1", tool.toolCall.toolCallId)
            assertTrue(channel.written.contains("\"method\":\"session/prompt\""))
            assertTrue(channel.written.contains("\"text\":\"hola\""))
        } finally {
            client.close()
            scope.cancel()
        }
    }

    @Test
    fun permissionRequestIsEmittedAndAnswered() = runBlocking {
        val permissionRequest =
            """{"jsonrpc":"2.0","id":9,"method":"session/request_permission","params":{"sessionId":"sess-1","toolCall":{"toolCallId":"tc9","title":"Ejecutar comando","status":"in_progress"},"options":[{"optionId":"allow","name":"Permitir","kind":"allow_once"},{"optionId":"reject","name":"Rechazar","kind":"reject_once"}]}}"""
        val channel = QueueChannel()
        val (client, scope) = client(channel)
        try {
            val received = CompletableDeferred<PermissionRequest>()
            val collector = launch { received.complete(client.permissionRequests.receive()) }

            val initDeferred = async { withTimeout(5_000) { client.initialize() } }
            while (!channel.written.contains("initialize")) kotlinx.coroutines.delay(5)
            channel.send(initializeResponse)
            initDeferred.await()

            channel.send(permissionRequest)
            val request = withTimeout(5_000) { received.await() }
            collector.cancel()

            assertEquals("9", request.requestId)
            assertEquals("tc9", request.toolCall.toolCallId)
            assertEquals(listOf("allow", "reject"), request.options.map { it.optionId })
            assertTrue(request.options[0].kind == "allow_once")

            withTimeout(5_000) { client.respondPermission(request, PermissionOutcome.Selected("allow")) }
            assertTrue(channel.written.contains("\"id\":9"))
            assertTrue(channel.written.contains("\"outcome\":\"selected\""))
        } finally {
            client.close()
            scope.cancel()
        }
    }

    @Test
    fun promptErrorIsReturnedNotThrown() = runBlocking {
        // El prompt es la 2ª request de este cliente: responde con id 2.
        val errorResponse = """{"jsonrpc":"2.0","id":2,"error":{"code":-32603,"message":"Internal error","data":{"details":"Session not found"}}}"""
        val channel = QueueChannel()
        val (client, scope) = client(channel)
        try {
            val initDeferred = async { withTimeout(5_000) { client.initialize() } }
            while (!channel.written.contains("initialize")) kotlinx.coroutines.delay(5)
            channel.send(initializeResponse)
            initDeferred.await()

            val promptDeferred = async { withTimeout(5_000) { client.prompt("desconocida", "hola") } }
            while (!channel.written.contains("session/prompt")) kotlinx.coroutines.delay(5)
            channel.send(errorResponse)

            val result = promptDeferred.await()
            assertEquals(-32603, result.error?.code)
            assertNull(result.stopReason)
        } finally {
            client.close()
            scope.cancel()
        }
    }

    @Test
    fun unknownIncomingMethodGetsMethodNotFound() = runBlocking {
        val fsRequest = """{"jsonrpc":"2.0","id":11,"method":"fs/read_text_file","params":{"path":"/etc/hostname"}}"""
        val channel = QueueChannel()
        val (client, scope) = client(channel)
        try {
            val initDeferred = async { withTimeout(5_000) { client.initialize() } }
            while (!channel.written.contains("initialize")) kotlinx.coroutines.delay(5)
            channel.send(initializeResponse)
            initDeferred.await()

            channel.send(fsRequest)
            withTimeout(5_000) {
                while (!channel.written.contains("\"id\":11")) kotlinx.coroutines.delay(10)
            }
            assertTrue(channel.written.contains("\"error\":{\"code\":-32601"))
        } finally {
            client.close()
            scope.cancel()
        }
    }

    @Test
    fun concurrentPromptsMatchTheirOwnIds() = runBlocking {
        val channel = QueueChannel()
        val (client, scope) = client(channel)
        try {
            val initDeferred = async { withTimeout(5_000) { client.initialize() } }
            while (!channel.written.contains("initialize")) kotlinx.coroutines.delay(5)
            channel.send(initializeResponse)
            initDeferred.await()

            // Dos prompts en paralelo; el agente responde el segundo primero.
            val a = async { withTimeout(5_000) { client.prompt("sess-1", "A") } }
            val b = async { withTimeout(5_000) { client.prompt("sess-1", "B") } }
            while (!channel.written.contains("\"text\":\"B\"")) kotlinx.coroutines.delay(5)
            channel.send("""{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}""")
            channel.send("""{"jsonrpc":"2.0","id":2,"result":{"stopReason":"max_tokens"}}""")
            assertEquals("max_tokens", a.await().stopReason)
            assertEquals("end_turn", b.await().stopReason)
        } finally {
            client.close()
            scope.cancel()
        }
    }

    @Test
    fun cancelNotificationHasNoId() = runBlocking {
        val channel = QueueChannel()
        val (client, scope) = client(channel)
        try {
            withTimeout(5_000) { client.cancel("sess-1") }
            assertTrue(channel.written.contains("\"method\":\"session/cancel\""))
            assertTrue(!channel.written.contains("\"id\":"))
        } finally {
            client.close()
            scope.cancel()
        }
    }

    /** `configOptions` real de `pi-acp` (modelo + nivel de "thinking"), ya tipado. */
    @Test
    fun newSessionParsesTypedConfigOptions() = runBlocking {
        val channel = QueueChannel()
        val (client, scope) = client(channel)
        try {
            val sessionDeferred = async { withTimeout(5_000) { client.newSession("/tmp") } }
            while (!channel.written.contains("\"method\":\"session/new\"")) kotlinx.coroutines.delay(5)
            // Una sola línea NDJSON: un salto de línea real aquí partiría el
            // mensaje en dos "líneas" para el framer (ver [QueueChannel.send]).
            channel.send(
                """{"jsonrpc":"2.0","id":1,"result":{"sessionId":"sess-1","modes":null,"configOptions":[""" +
                    """{"id":"model","name":"Model","type":"select","currentValue":"anthropic/claude-sonnet-5",""" +
                    """"options":[{"value":"anthropic/claude-sonnet-5","name":"anthropic/claude-sonnet-5"}]},""" +
                    """{"id":"thought_level","name":"Thinking","category":"thought_level","type":"select","currentValue":"medium",""" +
                    """"options":[{"value":"off","name":"Thinking: off"},{"value":"medium","name":"Thinking: medium"}]}""" +
                    """]}}""",
            )
            val result = sessionDeferred.await()
            assertEquals(2, result.configOptions?.size)
            val model = result.configOptions!!.first { it.id == "model" }
            assertEquals("anthropic/claude-sonnet-5", model.currentValue)
            assertEquals(1, model.options.size)
            val thinking = result.configOptions!!.first { it.id == "thought_level" }
            assertEquals("medium", thinking.currentValue)
            assertEquals(2, thinking.options.size)
        } finally {
            client.close()
            scope.cancel()
        }
    }

    @Test
    fun setConfigOptionSendsConfigIdAndValueAndParsesUpdatedList() = runBlocking {
        val channel = QueueChannel()
        val (client, scope) = client(channel)
        try {
            val resultDeferred = async { withTimeout(5_000) { client.setConfigOption("sess-1", "model", "openai/gpt-5") } }
            while (!channel.written.contains("\"method\":\"session/set_config_option\"")) kotlinx.coroutines.delay(5)
            assertTrue(channel.written.contains("\"sessionId\":\"sess-1\""))
            assertTrue(channel.written.contains("\"configId\":\"model\""))
            assertTrue(channel.written.contains("\"value\":\"openai/gpt-5\""))

            val id = Regex("\"id\":(\\d+),\"method\":\"session/set_config_option\"").find(channel.written)!!.groupValues[1]
            channel.send(
                """{"jsonrpc":"2.0","id":$id,"result":{"configOptions":[""" +
                    """{"id":"model","name":"Model","type":"select","currentValue":"openai/gpt-5","options":[]}]}}""",
            )
            val configOptions = resultDeferred.await()
            assertEquals("openai/gpt-5", configOptions.single().currentValue)
        } finally {
            client.close()
            scope.cancel()
        }
    }

    /**
     * Reproduce el crash reportado al cerrar un tab / matar el agente: SSHJ
     * traduce la interrupción del hilo de lectura en `InterruptedIOException`
     * (no `InterruptedException`), así que `runInterruptible` no la convierte
     * en `CancellationException` y llega al reader loop como una excepción
     * real. Sin el `catch` en [AcpClient.start], esto escapaba al scope raíz
     * (sin `CoroutineExceptionHandler`) y tumbaba toda la app.
     */
    @Test
    fun readFailureIsTreatedAsEofInsteadOfCrashingTheScope() = runBlocking {
        val uncaught = mutableListOf<Throwable>()
        val handler = CoroutineExceptionHandler { _, e -> uncaught.add(e) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
        val failingChannel = object : RawByteChannel {
            override suspend fun readChunk(buffer: ByteArray): Int = throw InterruptedIOException("interrupted read")
            override suspend fun write(bytes: ByteArray) {}
            override suspend fun flush() {}
            override fun close() {}
        }
        val client = AcpClient(NdjsonFramer(failingChannel), scope)
        val eofCalled = CompletableDeferred<Unit>()
        client.onEof = { eofCalled.complete(Unit) }
        client.start()
        try {
            withTimeout(5_000) { eofCalled.await() }
            kotlinx.coroutines.delay(50) // margen para que un escape tardío llegue al handler
            assertTrue(uncaught.isEmpty(), "no debería escapar ninguna excepción al scope: $uncaught")
        } finally {
            client.close()
            scope.cancel()
        }
    }
}
