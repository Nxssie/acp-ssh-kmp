package com.nxssie.acpssh.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        val command = RemoteAcpProcess.readerCommand("/tmp/run")
        assertTrue(command.startsWith("cd '/tmp/run' && "))
        assertTrue(command.endsWith("exec cat ${RemoteAcpProcess.STDOUT_FIFO}"))
    }

    @Test
    fun readerCommandKillsPreviousReaderBeforeRecordingItsOwnPid() {
        // Sin pty, cerrar el canal exec no le llega como señal al `cat` remoto:
        // sin este relevo, el lector huérfano de una conexión anterior podría
        // quedarse compitiendo con el nuevo por el siguiente mensaje del FIFO.
        val command = RemoteAcpProcess.readerCommand("/tmp/run")
        assertTrue(command.contains("kill \"\$(cat ${RemoteAcpProcess.READER_PID_FILE})\""))
        assertTrue(command.contains("echo \$\$ > ${RemoteAcpProcess.READER_PID_FILE}"))
    }

    @Test
    fun writerCommandAppendsStdinToStdinFifo() {
        val command = RemoteAcpProcess.writerCommand("/tmp/run")
        assertTrue(command.startsWith("cd '/tmp/run' && "))
        assertTrue(command.endsWith("exec cat >> ${RemoteAcpProcess.STDIN_FIFO}"))
    }

    @Test
    fun writerCommandKillsPreviousWriterBeforeRecordingItsOwnPid() {
        val command = RemoteAcpProcess.writerCommand("/tmp/run")
        assertTrue(command.contains("kill \"\$(cat ${RemoteAcpProcess.WRITER_PID_FILE})\""))
        assertTrue(command.contains("echo \$\$ > ${RemoteAcpProcess.WRITER_PID_FILE}"))
    }

    // --- writeSessionCommand --------------------------------------------------

    @Test
    fun writeSessionCommandWritesAtomicallyViaTempFileAndMove() {
        val command = RemoteAcpProcess.writeSessionCommand("/tmp/run", "sess-1", "/home/u/proj")
        assertTrue(command.startsWith("mkdir -p '/tmp/run' && "))
        assertTrue(command.contains("printf '%s\\n%s\\n' 'sess-1' '/home/u/proj' > '/tmp/run/${RemoteAcpProcess.SESSION_FILE}.tmp'"))
        assertTrue(command.endsWith("mv '/tmp/run/${RemoteAcpProcess.SESSION_FILE}.tmp' '/tmp/run/${RemoteAcpProcess.SESSION_FILE}'"))
    }

    @Test
    fun writeSessionCommandQuotesValuesWithShellMetacharacters() {
        val command = RemoteAcpProcess.writeSessionCommand("/tmp/it's a dir", "sess'\$(danger)", "/tmp/`cmd`")
        assertTrue(command.contains(RemoteAcpProcess.shellQuote("sess'\$(danger)")))
        assertTrue(command.contains(RemoteAcpProcess.shellQuote("/tmp/`cmd`")))
    }

    @Test
    fun writeSessionCommandRejectsNewlineInSessionId() {
        assertFailsWith<IllegalArgumentException> {
            RemoteAcpProcess.writeSessionCommand("/tmp/run", "sess\n1", "/home/u")
        }
    }

    @Test
    fun writeSessionCommandRejectsTabInSessionId() {
        assertFailsWith<IllegalArgumentException> {
            RemoteAcpProcess.writeSessionCommand("/tmp/run", "sess\t1", "/home/u")
        }
    }

    @Test
    fun writeSessionCommandRejectsNewlineInCwd() {
        assertFailsWith<IllegalArgumentException> {
            RemoteAcpProcess.writeSessionCommand("/tmp/run", "sess-1", "/home/u\n/evil")
        }
    }

    // --- killCommand / killScript ----------------------------------------------

    @Test
    fun killCommandWrapsScriptInShDashC() {
        val command = RemoteAcpProcess.killCommand("/tmp/run")
        assertTrue(command.startsWith("sh -c "))
        val quoted = command.removePrefix("sh -c ")
        assertEquals(RemoteAcpProcess.killScript("/tmp/run"), unquotePosixSingle(quoted))
    }

    @Test
    fun killScriptEmbedsQuotedRunDir() {
        val script = RemoteAcpProcess.killScript("/tmp/it's a dir")
        assertTrue(script.contains("D='/tmp/it'\"'\"'s a dir'"))
    }

    @Test
    fun killScriptVerifiesFdIdentityBeforeKilling() {
        val script = RemoteAcpProcess.killScript("/tmp/run")
        // Un pid reciclado tras un reinicio del host podría pertenecer a un
        // proceso ajeno: sin esto, "Terminar" podría matar algo que no es el agente.
        assertTrue(script.contains("/proc/\$pid/fd"))
        assertTrue(script.contains("readlink"))
        assertTrue(script.contains(RemoteAcpProcess.STDIN_FIFO))
        assertTrue(script.contains(RemoteAcpProcess.STDOUT_FIFO))
    }

    @Test
    fun killScriptSignalsProcessGroupWithEscalation() {
        val script = RemoteAcpProcess.killScript("/tmp/run")
        assertTrue(script.contains("kill -TERM -\"\$pid\""))
        assertTrue(script.contains("kill -KILL -\"\$pid\""))
        assertTrue(script.contains("sleep 1"))
    }

    @Test
    fun killScriptKillsReaderAndWriterRelaysToo() {
        val script = RemoteAcpProcess.killScript("/tmp/run")
        assertTrue(script.contains(RemoteAcpProcess.READER_PID_FILE))
        assertTrue(script.contains(RemoteAcpProcess.WRITER_PID_FILE))
    }

    @Test
    fun killScriptAlwaysCleansUpTheDirectory() {
        val script = RemoteAcpProcess.killScript("/tmp/run")
        assertTrue(script.contains("rm -rf \"\$D\""))
    }

    // --- listCommand / listScript ------------------------------------------------

    @Test
    fun listCommandWrapsScriptInShDashC() {
        val command = RemoteAcpProcess.listCommand(".acp-ssh-kmp")
        assertTrue(command.startsWith("sh -c "))
        val quoted = command.removePrefix("sh -c ")
        assertEquals(RemoteAcpProcess.listScript(".acp-ssh-kmp"), unquotePosixSingle(quoted))
    }

    @Test
    fun listScriptEmbedsQuotedBaseDir() {
        val script = RemoteAcpProcess.listScript("/tmp/it's a base")
        assertTrue(script.contains("BASE='/tmp/it'\"'\"'s a base'"))
    }

    @Test
    fun listScriptGuardsMissingBaseAndUnmatchedGlob() {
        val script = RemoteAcpProcess.listScript("/tmp/x")
        assertTrue(script.contains("[ -d \"\$BASE\" ]"))
        assertTrue(script.contains("[ -d \"\$d\" ] || continue"))
    }

    @Test
    fun listScriptAlwaysEmitsSentinel() {
        val script = RemoteAcpProcess.listScript("/tmp/x")
        assertTrue(script.trim().endsWith("printf '%s\\n' ${RemoteAcpProcess.LIST_END}"))
    }

    @Test
    fun listScriptValidatesStatOutputNumericallyBeforeUse() {
        // `stat -f %m` sobre GNU cuando falta el fichero puede imprimir info del
        // filesystem por stdout en vez de fallar limpio: sin esta validación
        // numérica, esa salida se colaría como si fuera un epoch real.
        val script = RemoteAcpProcess.listScript("/tmp/x")
        assertTrue(script.contains("case \$_m in ''|*[!0-9]*)"))
    }

    @Test
    fun listScriptDoesNotCollideWithFakeTransportRoutingSubstrings() {
        val command = RemoteAcpProcess.listCommand(".acp-ssh-kmp")
        assertFalse("cat >>" in command)
        assertFalse("cat ${RemoteAcpProcess.STDOUT_FIFO}" in command)
        assertFalse("mkfifo" in command)
        assertTrue(command.trim() != "pwd")
    }

    // --- parseListOutput ------------------------------------------------------

    @Test
    fun parseListOutputReturnsNullWithoutSentinel() {
        val raw = "tab-1\tLIVE\t123\t45\tATTACHED\tsess-1\t/home/u\n"
        assertNull(RemoteAcpProcess.parseListOutput(raw))
    }

    @Test
    fun parseListOutputSucceedsWithOnlySentinel() {
        val entries = RemoteAcpProcess.parseListOutput("${RemoteAcpProcess.LIST_END}\n")
        assertEquals(emptyList(), entries)
    }

    @Test
    fun parseListOutputParsesFullRecord() {
        val raw = "tab-1\tLIVE\t123\t45\tATTACHED\tsess-1\t/home/u/proj\n${RemoteAcpProcess.LIST_END}\n"
        val entries = RemoteAcpProcess.parseListOutput(raw)!!
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("tab-1", e.dirName)
        assertTrue(e.alive)
        assertEquals("123", e.pid)
        assertEquals(45L, e.idleSeconds)
        assertEquals(true, e.attached)
        assertEquals("sess-1", e.sessionId)
        assertEquals("/home/u/proj", e.cwd)
    }

    @Test
    fun parseListOutputKeepsTabsInsideCwdAsLastField() {
        val raw = "tab-1\tSTALE\t-\t-\t-\t-\t/tmp/with\ttab\n${RemoteAcpProcess.LIST_END}\n"
        val entries = RemoteAcpProcess.parseListOutput(raw)!!
        assertEquals("/tmp/with\ttab", entries.single().cwd)
    }

    @Test
    fun parseListOutputMapsDashPlaceholdersToNull() {
        val raw = "tab-1\tSTALE\t-\t-\t-\t-\t-\n${RemoteAcpProcess.LIST_END}\n"
        val e = RemoteAcpProcess.parseListOutput(raw)!!.single()
        assertFalse(e.alive)
        assertNull(e.pid)
        assertNull(e.idleSeconds)
        assertNull(e.attached)
        assertNull(e.sessionId)
        assertNull(e.cwd)
    }

    @Test
    fun parseListOutputIgnoresNoiseLinesBeforeRecords() {
        val raw = "Welcome to Ubuntu\nMOTD line\ntab-1\tLIVE\t1\t2\tFREE\t-\t-\n${RemoteAcpProcess.LIST_END}\n"
        val entries = RemoteAcpProcess.parseListOutput(raw)!!
        assertEquals(1, entries.size)
        assertEquals("tab-1", entries[0].dirName)
    }

    @Test
    fun parseListOutputIgnoresLinesWithWrongFieldCount() {
        val raw = "tab-1\tLIVE\t1\n${RemoteAcpProcess.LIST_END}\n"
        assertEquals(emptyList(), RemoteAcpProcess.parseListOutput(raw))
    }

    @Test
    fun parseListOutputIgnoresUnrecognizedState() {
        val raw = "tab-1\tWEIRD\t1\t2\tFREE\t-\t-\n${RemoteAcpProcess.LIST_END}\n"
        assertEquals(emptyList(), RemoteAcpProcess.parseListOutput(raw))
    }

    @Test
    fun parseListOutputRejectsUnsafeDirNames() {
        val raw = listOf("..", "/etc", "").joinToString("\n") { dir ->
            "$dir\tLIVE\t1\t2\tFREE\t-\t-"
        } + "\n${RemoteAcpProcess.LIST_END}\n"
        assertEquals(emptyList(), RemoteAcpProcess.parseListOutput(raw))
    }

    @Test
    fun parseListOutputMapsAttachedAndFree() {
        val raw = "tab-1\tLIVE\t1\t2\tATTACHED\t-\t-\ntab-2\tLIVE\t1\t2\tFREE\t-\t-\n${RemoteAcpProcess.LIST_END}\n"
        val entries = RemoteAcpProcess.parseListOutput(raw)!!
        assertEquals(true, entries.first { it.dirName == "tab-1" }.attached)
        assertEquals(false, entries.first { it.dirName == "tab-2" }.attached)
    }
}
