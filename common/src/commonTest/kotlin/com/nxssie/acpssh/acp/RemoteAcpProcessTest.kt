package com.nxssie.acpssh.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteAcpProcessTest {

    // --- shellQuote --------------------------------------------------------

    @Test
    fun shellQuoteWrapsPlainValueInSingleQuotes() {
        assertEquals("'plain'", RemoteAcpProcess.shellQuote("plain"))
    }

    @Test
    fun shellQuotePreservesSpacesAsOneWord() {
        // Sigue siendo una sola palabra shell pese al espacio, gracias a las comillas.
        assertEquals("'a b'", RemoteAcpProcess.shellQuote("a b"))
    }

    @Test
    fun shellQuoteNeutralizesDollarAndBackticksAndDoubleQuotes() {
        val original = "\$(rm -rf /) `evil` \"x\""
        val quoted = RemoteAcpProcess.shellQuote(original)
        // Todo lo peligroso queda dentro de comillas simples: sin expansión posible.
        assertEquals("'" + original + "'", quoted)
    }

    @Test
    fun shellQuoteRoundTripsEmbeddedSingleQuote() {
        val original = "it's a \"test\" with \$var and `cmd`"
        val quoted = RemoteAcpProcess.shellQuote(original)
        assertEquals(original, unquotePosixSingle(quoted))
    }

    @Test
    fun shellQuoteRoundTripsOnlyQuotes() {
        val original = "'''"
        assertEquals(original, unquotePosixSingle(RemoteAcpProcess.shellQuote(original)))
    }

    /** Deshace [RemoteAcpProcess.shellQuote] simulando las reglas POSIX de comillas simples. */
    private fun unquotePosixSingle(quoted: String): String {
        require(quoted.startsWith("'") && quoted.endsWith("'"))
        return quoted.substring(1, quoted.length - 1).replace("'\"'\"'", "'")
    }

    // --- launchScript --------------------------------------------------------

    @Test
    fun launchScriptEmbedsQuotedRunDirAndAgentCommand() {
        val script = RemoteAcpProcess.launchScript("/tmp/run dir", "my-agent --flag")
        assertTrue(script.contains("RUN_DIR='/tmp/run dir'"))
        assertTrue(script.contains("AGENT_CMD='my-agent --flag'"))
    }

    @Test
    fun launchScriptChecksPidFileBeforeRelaunching() {
        val script = RemoteAcpProcess.launchScript("/tmp/x", "agent")
        assertTrue(script.contains("kill -0"))
        assertTrue(script.contains("ALREADY_RUNNING"))
        assertTrue(script.contains("STARTED"))
    }

    @Test
    fun launchScriptDetachesWithSetsidAndKeepsFdsOpen() {
        val script = RemoteAcpProcess.launchScript("/tmp/x", "agent")
        assertTrue(script.contains("setsid sh -c"))
        // Los fd 3/4 mantienen el conteo de lectores/escritores del FIFO en >0
        // aunque el cliente se desconecte, evitando EOF/EPIPE en el agente.
        assertTrue(script.contains("exec 3<>${RemoteAcpProcess.STDIN_FIFO}"))
        assertTrue(script.contains("exec 4<>${RemoteAcpProcess.STDOUT_FIFO}"))
        assertTrue(script.contains("2>>${RemoteAcpProcess.STDERR_LOG}"))
    }

    @Test
    fun launchScriptNeverBreaksOutOfInnerSingleQuotesForArbitraryAgentCommand() {
        // El agentCommand llega como argv (positional $0), no interpolado dentro
        // del bloque `setsid sh -c '...'`: cualquier comilla simple que meta el
        // usuario en RUN_DIR/AGENT_CMD debe quedar neutralizada por shellQuote,
        // sin romper la comilla simple que abre ese bloque.
        val script = RemoteAcpProcess.launchScript("/tmp/it's a dir", "agent 'quoted' \$(danger)")
        val innerBlockStart = script.indexOf("setsid sh -c '") + "setsid sh -c '".length
        val innerBlockEnd = script.indexOf("' \"", innerBlockStart)
        assertTrue(innerBlockEnd > innerBlockStart, "no se encontró el cierre del bloque interno")
        val innerBlock = script.substring(innerBlockStart, innerBlockEnd)
        assertTrue("'" !in innerBlock, "el bloque interno no debe contener comillas simples sin escapar: $innerBlock")
    }

    // --- readerCommand / writerCommand --------------------------------------

    @Test
    fun readerCommandCatsStdoutFifoFromRunDir() {
        assertEquals(
            "cd '/tmp/run' && exec cat ${RemoteAcpProcess.STDOUT_FIFO}",
            RemoteAcpProcess.readerCommand("/tmp/run"),
        )
    }

    @Test
    fun writerCommandAppendsStdinToStdinFifo() {
        assertEquals(
            "cd '/tmp/run' && exec cat >> ${RemoteAcpProcess.STDIN_FIFO}",
            RemoteAcpProcess.writerCommand("/tmp/run"),
        )
    }
}
