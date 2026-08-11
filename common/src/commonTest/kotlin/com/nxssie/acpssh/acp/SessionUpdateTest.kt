package com.nxssie.acpssh.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Decodificación de `session/update` (y shapes relacionados) contra mensajes
 * reales capturados del adaptador `claude-agent-acp` 0.66.0 en este entorno,
 * más las formas sintéticas definidas por el spec v1.
 */
class SessionUpdateTest {

    private fun update(json: String): SessionUpdate =
        SessionUpdate.from(AcpJson.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject)

    /** Captura real del adaptador tras `session/new`. */
    @Test
    fun availableCommandsUpdateFromRealAdapter() {
        val u = update(
            """{"sessionUpdate":"available_commands_update","availableCommands":[{"name":"commit","description":"Create atomic git commits","input":null},{"name":"compact","input":{"hint":"<text>"}}]}""",
        )
        val commands = assertIs<SessionUpdate.AvailableCommandsUpdate>(u)
        assertEquals(2, commands.commands.size)
        assertEquals("commit", commands.commands[0]["name"]!!.let { it as kotlinx.serialization.json.JsonPrimitive }.content)
    }

    @Test
    fun agentMessageChunkCarriesTextAndMessageId() {
        val u = update(
            """{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Hola"},"messageId":"msg-1"}""",
        )
        val chunk = assertIs<SessionUpdate.AgentMessageChunk>(u)
        assertEquals("Hola", (chunk.chunk.content as ContentBlock.Text).text)
        assertEquals("msg-1", chunk.chunk.messageId)
    }

    @Test
    fun thoughtChunkIsSeparateKind() {
        val u = update(
            """{"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"razonando"},"messageId":"t-1"}""",
        )
        assertIs<SessionUpdate.AgentThoughtChunk>(u)
    }

    @Test
    fun toolCallParsesRequiredFields() {
        val u = update(
            """{"sessionUpdate":"tool_call","toolCall":{"toolCallId":"tc1","title":"Leer archivo","kind":"read","status":"in_progress","content":[]}}""",
        )
        val tool = assertIs<SessionUpdate.ToolCall>(u)
        assertEquals("tc1", tool.toolCall.toolCallId)
        assertEquals("read", tool.toolCall.kind)
        assertEquals("in_progress", tool.toolCall.status)
    }

    @Test
    fun toolCallUpdateCarriesDiff() {
        val u = update(
            """{"sessionUpdate":"tool_call_update","toolCallUpdate":{"toolCallId":"tc1","status":"completed","content":[{"type":"diff","path":"/tmp/a.txt","oldText":"hola\nmundo","newText":"hola\nadios"}]}}""",
        )
        val update = assertIs<SessionUpdate.ToolCallUpdate>(u)
        assertEquals("completed", update.toolCallUpdate.status)
        val diff = update.toolCallUpdate.content.single() as ToolCallContent.Diff
        assertEquals("/tmp/a.txt", diff.path)
        assertEquals("hola\nadios", diff.newText)
    }

    @Test
    fun planParsesEntries() {
        val u = update(
            """{"sessionUpdate":"plan","plan":{"entries":[{"content":"Revisar código","priority":"high","status":"in_progress"},{"content":"Escribir tests","priority":"medium","status":"pending"}]}}""",
        )
        val plan = assertIs<SessionUpdate.Plan>(u)
        assertEquals(2, plan.plan.entries.size)
        assertEquals("Revisar código", plan.plan.entries[0].content)
        assertEquals("in_progress", plan.plan.entries[0].status)
    }

    @Test
    fun unknownTagIsPreservedRaw() {
        val u = update("""{"sessionUpdate":"futuro_unknown","algo":123}""")
        val unknown = assertIs<SessionUpdate.Unknown>(u)
        assertEquals("futuro_unknown", unknown.tag)
    }

    @Test
    fun missingOptionalFieldsDoNotBreakParse() {
        val u = update("""{"sessionUpdate":"tool_call","toolCall":{"toolCallId":"tc2"}}""")
        val tool = assertIs<SessionUpdate.ToolCall>(u)
        assertEquals("", tool.toolCall.title)
        assertTrue(tool.toolCall.content.isEmpty())
    }

    /** El tag del spec usa snake_case incluso en tags de varias palabras. */
    @Test
    fun snakeCaseTags() {
        assertIs<SessionUpdate.CurrentModeUpdate>(
            update("""{"sessionUpdate":"current_mode_update","currentModeId":"default"}"""),
        )
        assertIs<SessionUpdate.SessionInfoUpdate>(
            update("""{"sessionUpdate":"session_info_update","info":{"title":"x"}}"""),
        )
        assertIs<SessionUpdate.UsageUpdate>(
            update("""{"sessionUpdate":"usage_update","usage":{"totalTokens":10}}"""),
        )
    }

    /**
     * El spec (confirmado contra `agentclientprotocol.com/protocol/v1/session-config-options`
     * y contra la fuente real de `pi-acp`, que es quien expone esto hoy —
     * modelo y nivel de "thinking") manda la clave PLURAL `configOptions` con
     * el array completo, no un `configOption` singular.
     */
    @Test
    fun configOptionUpdateParsesFullTypedList() {
        val u = update(
            """{"sessionUpdate":"config_option_update","configOptions":[
                {"id":"model","name":"Model","description":"Select the model for this session","category":"model",
                 "type":"select","currentValue":"anthropic/claude-sonnet-5",
                 "options":[{"value":"anthropic/claude-sonnet-5","name":"anthropic/claude-sonnet-5","description":null},
                            {"value":"openai/gpt-5","name":"openai/gpt-5"}]}
            ]}""",
        )
        val configUpdate = assertIs<SessionUpdate.ConfigOptionUpdate>(u)
        assertEquals(1, configUpdate.configOptions.size)
        val model = configUpdate.configOptions.single()
        assertEquals("model", model.id)
        assertEquals("Model", model.name)
        assertEquals("select", model.type)
        assertEquals("anthropic/claude-sonnet-5", model.currentValue)
        assertEquals(2, model.options.size)
        assertEquals("openai/gpt-5", model.options[1].value)
    }

    @Test
    fun configOptionUpdateWithMissingArrayIsEmptyNotBroken() {
        val u = update("""{"sessionUpdate":"config_option_update"}""")
        val configUpdate = assertIs<SessionUpdate.ConfigOptionUpdate>(u)
        assertTrue(configUpdate.configOptions.isEmpty())
    }
}
