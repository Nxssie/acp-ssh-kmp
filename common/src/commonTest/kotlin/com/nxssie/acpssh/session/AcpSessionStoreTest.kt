package com.nxssie.acpssh.session

import com.nxssie.acpssh.acp.ContentBlock
import com.nxssie.acpssh.acp.ContentChunk
import com.nxssie.acpssh.acp.SessionUpdate
import com.nxssie.acpssh.acp.ToolCallContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reducer de la sesión de chat: burbujas, tool calls, plan, permisos, busy. */
class AcpSessionStoreTest {

    @Test
    fun chunksOfSameMessageAppend() {
        val store = AcpSessionStore()
        store.onUpdate(chunk("Hola", "m1"))
        store.onUpdate(chunk(" mundo", "m1"))
        val messages = store.state.value.messages
        assertEquals(1, messages.size)
        assertEquals("Hola mundo", messages[0].text)
        assertTrue(messages[0].streaming)
    }

    @Test
    fun newMessageIdStartsNewBubble() {
        val store = AcpSessionStore()
        store.onUpdate(chunk("Hola", "m1"))
        store.onUpdate(chunk("Adios", "m2"))
        assertEquals(2, store.state.value.messages.size)
        assertEquals("Adios", store.state.value.messages[1].text)
    }

    @Test
    fun chunksWithoutMessageIdStillAppend() {
        // El agente real (claude-code-acp) no manda messageId en sus chunks.
        val store = AcpSessionStore()
        store.onUpdate(chunk("P", null))
        store.onUpdate(chunk("ONG", null))
        val messages = store.state.value.messages
        assertEquals(1, messages.size)
        assertEquals("PONG", messages[0].text)
    }

    @Test
    fun turnEndThenNewChunkWithoutMessageIdStartsFreshBubble() {
        val store = AcpSessionStore()
        store.onUpdate(chunk("primera", null))
        store.onTurnEnd()
        store.onUpdate(chunk("segunda", null))
        val messages = store.state.value.messages
        assertEquals(2, messages.size)
        assertEquals("primera", messages[0].text)
        assertEquals("segunda", messages[1].text)
    }

    @Test
    fun userMessageChunkFromReplayShowsAsUserBubble() {
        // session/load repone el historial con user_message_chunk (el agente
        // real nunca lo manda en un turno en vivo, verificado): antes se
        // ignoraba siempre, así que el mensaje original del usuario no
        // aparecía al retomar una sesión.
        val store = AcpSessionStore()
        store.onUpdate(
            SessionUpdate.UserMessageChunk(ContentChunk(content = ContentBlock.Text("hola desde antes"), messageId = null)),
        )
        val messages = store.state.value.messages
        assertEquals(1, messages.size)
        assertEquals(ChatRole.USER, messages[0].role)
        assertEquals("hola desde antes", messages[0].text)
    }

    @Test
    fun thoughtsAreSeparateRole() {
        val store = AcpSessionStore()
        store.onUpdate(
            SessionUpdate.AgentThoughtChunk(ContentChunk(content = ContentBlock.Text("razono"), messageId = "t1")),
        )
        assertEquals(ChatRole.THOUGHT, store.state.value.messages[0].role)
    }

    @Test
    fun userPromptAddsBubbleAndBusy() {
        val store = AcpSessionStore()
        store.onUserPrompt("haz algo")
        assertEquals(ChatRole.USER, store.state.value.messages[0].role)
        assertTrue(store.state.value.busy)
        store.onTurnEnd()
        assertFalse(store.state.value.busy)
        assertFalse(store.state.value.messages[0].streaming)
    }

    @Test
    fun toolCallThenUpdateMergesState() {
        val store = AcpSessionStore()
        store.onUpdate(
            SessionUpdate.ToolCall(
                com.nxssie.acpssh.acp.AcpToolCall(
                    toolCallId = "tc1",
                    title = "Leer",
                    kind = "read",
                    status = "in_progress",
                    content = emptyList(),
                    rawInput = null,
                    rawOutput = null,
                ),
            ),
        )
        store.onUpdate(
            SessionUpdate.ToolCallUpdate(
                com.nxssie.acpssh.acp.AcpToolCallUpdate(
                    toolCallId = "tc1",
                    title = null,
                    kind = null,
                    status = "completed",
                    content = listOf(
                        ToolCallContent.Diff("/tmp/a.txt", "hola", "adios"),
                    ),
                    rawInput = null,
                    rawOutput = null,
                ),
            ),
        )
        val tool = store.state.value.toolCalls.single()
        assertEquals("completed", tool.status)
        assertEquals(1, tool.diffs.size)
        assertEquals("/tmp/a.txt", tool.diffs[0].path)
    }

    @Test
    fun unknownToolCallUpdateIsIgnored() {
        val store = AcpSessionStore()
        store.onUpdate(
            SessionUpdate.ToolCallUpdate(
                com.nxssie.acpssh.acp.AcpToolCallUpdate(
                    toolCallId = "nope", title = null, kind = null, status = "completed",
                    content = emptyList(), rawInput = null, rawOutput = null,
                ),
            ),
        )
        assertTrue(store.state.value.toolCalls.isEmpty())
    }

    @Test
    fun planIsReplaced() {
        val store = AcpSessionStore()
        store.onUpdate(
            SessionUpdate.Plan(
                com.nxssie.acpssh.acp.AcpPlan(
                    listOf(
                        com.nxssie.acpssh.acp.PlanEntry("a", "high", "in_progress"),
                        com.nxssie.acpssh.acp.PlanEntry("b", "low", "pending"),
                    ),
                ),
            ),
        )
        assertEquals(2, store.state.value.plan.size)
        assertEquals("in_progress", store.state.value.plan[0].status)
    }

    @Test
    fun toggleToolCallExpands() {
        val store = AcpSessionStore()
        store.onUpdate(
            SessionUpdate.ToolCall(
                com.nxssie.acpssh.acp.AcpToolCall("tc1", "x", null, null, emptyList(), null, null),
            ),
        )
        assertFalse(store.state.value.toolCalls[0].expanded)
        store.toggleToolCall("tc1")
        assertTrue(store.state.value.toolCalls[0].expanded)
    }

    @Test
    fun resetClearsEverything() {
        val store = AcpSessionStore()
        store.onUserPrompt("x")
        store.onSessionStarted("agente", "s1")
        store.reset()
        assertNull(store.state.value.sessionId)
        assertTrue(store.state.value.messages.isEmpty())
    }

    @Test
    fun timelineInterleavesMessagesAndToolCallsByArrivalOrder() {
        // Reproduce el reporte real: user -> tool call -> respuesta -> otra
        // tool call -> respuesta. Antes la UI mostraba todos los mensajes
        // juntos y luego todas las tool calls, perdiendo este orden.
        val store = AcpSessionStore()
        store.onUserPrompt("lee el archivo")
        store.onUpdate(toolCall("tc1"))
        store.onUpdate(chunk("Leído.", "m1"))
        store.onUpdate(toolCallUpdate("tc1", "completed")) // merge: no debe moverse
        store.onUpdate(toolCall("tc2"))
        store.onUpdate(chunk("Listo.", "m2"))

        val timeline = store.state.value.timeline
        val kinds = timeline.map { ref ->
            when (ref) {
                is TimelineRef.Msg -> "msg:" + store.state.value.messages[ref.index].text
                is TimelineRef.Tool -> "tool:" + ref.id
            }
        }
        assertEquals(
            listOf("msg:lee el archivo", "tool:tc1", "msg:Leído.", "tool:tc2", "msg:Listo."),
            kinds,
        )
        // La actualización de tc1 (merge) no agregó una segunda entrada.
        assertEquals(1, timeline.count { it is TimelineRef.Tool && it.id == "tc1" })
    }

    private fun toolCall(id: String): SessionUpdate =
        SessionUpdate.ToolCall(com.nxssie.acpssh.acp.AcpToolCall(id, "Herramienta", null, null, emptyList(), null, null))

    private fun toolCallUpdate(id: String, status: String): SessionUpdate =
        SessionUpdate.ToolCallUpdate(
            com.nxssie.acpssh.acp.AcpToolCallUpdate(
                toolCallId = id, title = null, kind = null, status = status,
                content = emptyList(), rawInput = null, rawOutput = null,
            ),
        )

    private fun chunk(text: String, messageId: String?): SessionUpdate =
        SessionUpdate.AgentMessageChunk(
            ContentChunk(content = ContentBlock.Text(text), messageId = messageId),
        )
}
