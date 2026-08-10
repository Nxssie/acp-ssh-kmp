package com.nxssie.acpssh.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Framing JSON-RPC: detección de tipo de mensaje entrante por la forma
 * (respuesta / notificación / request) y construcción de los mensajes de
 * salida del cliente.
 */
class AcpProtocolTest {

    @Test
    fun responseWithResultIsDetected() {
        val msg = parseRpc("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}""")
        assertTrue(msg is RpcIncoming.Response)
        assertEquals("1", msg.id)
        assertNull(msg.error)
        val obj = msg.result as kotlinx.serialization.json.JsonObject
        assertEquals("1", obj["protocolVersion"].toString().trim('"'))
    }

    @Test
    fun responseWithErrorIsDetected() {
        val msg = parseRpc(
            """{"jsonrpc":"2.0","id":3,"error":{"code":-32603,"message":"Internal error","data":{"details":"Session not found"}}}""",
        )
        assertTrue(msg is RpcIncoming.Response)
        assertEquals(-32603, msg.error?.code)
        assertEquals("Internal error", msg.error?.message)
    }

    @Test
    fun notificationWithoutIdIsDetected() {
        val msg = parseRpc(
            """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":{"sessionUpdate":"current_mode_update","currentModeId":"default"}}}""",
        )
        assertTrue(msg is RpcIncoming.Notification)
        assertEquals("session/update", msg.method)
    }

    @Test
    fun incomingRequestHasMethodAndId() {
        val msg = parseRpc(
            """{"jsonrpc":"2.0","id":7,"method":"session/request_permission","params":{"sessionId":"s1","toolCall":{"toolCallId":"t1"},"options":[]}}""",
        )
        assertTrue(msg is RpcIncoming.Request)
        assertEquals("7", msg.id)
        assertEquals("session/request_permission", msg.method)
    }

    @Test
    fun malformedLinesAreIgnored() {
        assertNull(parseRpc("not json"))
        assertNull(parseRpc("""{"foo":1}"""))
        assertNull(parseRpc(""))
    }

    @Test
    fun requestBuilderProducesValidJsonRpc() {
        val json = RpcOut.request(
            1,
            "initialize",
            encodeToJson(InitializeParams()),
        )
        val parsed = parseRpc(json)
        assertTrue(parsed is RpcIncoming.Request)
        assertEquals("1", parsed.id)
        assertEquals("initialize", parsed.method)
    }

    @Test
    fun notificationBuilderHasNoId() {
        val json = RpcOut.notification("session/cancel", encodeToJson(SessionIdParams("s1")))
        assertTrue(parseRpc(json) is RpcIncoming.Notification)
    }

    @Test
    fun responseBuilderForPermissionOutcome() {
        val selected = RpcOut.response("7", PermissionOutcome.Selected("allow").toJson())
        assertTrue(parseRpc(selected) is RpcIncoming.Response)
        assertTrue(selected.contains(""""outcome":"selected""""))
        assertTrue(selected.contains(""""optionId":"allow""""))

        val cancelled = RpcOut.response("7", PermissionOutcome.Cancelled.toJson())
        assertTrue(cancelled.contains(""""outcome":"cancelled""""))
    }

    @Test
    fun unknownMethodGetsErrorResponse() {
        val json = RpcOut.errorResponse("9", -32601, "Method not found")
        val parsed = parseRpc(json)
        assertTrue(parsed is RpcIncoming.Response)
        assertEquals(-32601, parsed.error?.code)
    }
}
