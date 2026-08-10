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

    private fun chunk(text: String, messageId: String): SessionUpdate =
        SessionUpdate.AgentMessageChunk(
            ContentChunk(content = ContentBlock.Text(text), messageId = messageId),
        )
}
