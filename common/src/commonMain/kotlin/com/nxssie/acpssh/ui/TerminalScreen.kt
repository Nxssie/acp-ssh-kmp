package com.nxssie.acpssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxssie.acpssh.session.TerminalHost
import com.nxssie.acpssh.terminal.Ansi
import com.nxssie.acpssh.terminal.Cell
import com.nxssie.acpssh.terminal.ColorRef
import com.nxssie.acpssh.terminal.TerminalColors

private val TerminalFontSize = 13.sp

@OptIn(ExperimentalTextApi::class)
@Composable
fun TerminalScreen(host: TerminalHost) {
    val state by host.screen.collectAsState()
    val density = LocalDensity.current

    val textMeasurer = rememberTextMeasurer()
    val metrics = remember {
        textMeasurer.measure(
            AnnotatedString("M"),
            style = TextStyle(fontSize = TerminalFontSize, fontFamily = FontFamily.Monospace),
        )
    }
    val cellWidth = metrics.size.width.toFloat()
    val cellHeight = metrics.size.height.toFloat()

    var lastCols by remember { mutableStateOf(-1) }
    var lastRows by remember { mutableStateOf(-1) }

    var input by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(Color(0xFF101010.toInt()))) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { size ->
                    if (cellWidth > 0 && cellHeight > 0) {
                        val cols = (size.width / cellWidth).toInt().coerceAtLeast(20)
                        val rows = (size.height / cellHeight).toInt().coerceAtLeast(5)
                        if (cols != lastCols || rows != lastRows) {
                            lastCols = cols
                            lastRows = rows
                            host.resize(cols, rows)
                        }
                    }
                }
        ) {
            if (cellHeight > 0) {
                Column(Modifier.fillMaxSize().padding(4.dp)) {
                    for (r in 0 until state.rows) {
                        val rowText = remember(state, r) { buildRow(state.screen[r]) }
                        Text(
                            rowText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = TerminalFontSize,
                            lineHeight = with(density) { (cellHeight / density.density).sp },
                            color = Color.White,
                        )
                    }
                }
                if (state.cursorVisible) {
                    val x = with(density) { (state.cursorCol * cellWidth).toDp() + 4.dp }
                    val y = with(density) { (state.cursorRow * cellHeight).toDp() + 4.dp }
                    Box(
                        Modifier
                            .offset(x = x, y = y)
                            .width(with(density) { cellWidth.toDp() })
                            .height(with(density) { cellHeight.toDp() })
                            .background(Color.White.copy(alpha = 0.7f))
                    )
                }
            }
        }

        // Toolbar 1: teclas de control
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KeyButton("Esc", Modifier.weight(1f)) { host.send(byteArrayOf(0x1b)) }
            KeyButton("Tab", Modifier.weight(1f)) { host.send(byteArrayOf(0x09)) }
            KeyButton("Ctrl+C", Modifier.weight(1f)) { host.send(byteArrayOf(Ansi.ctrl('c'))) }
            KeyButton("Ctrl+D", Modifier.weight(1f)) { host.send(byteArrayOf(Ansi.ctrl('d'))) }
            KeyButton("Ctrl+Z", Modifier.weight(1f)) { host.send(byteArrayOf(Ansi.ctrl('z'))) }
            KeyButton("Ctrl+L", Modifier.weight(1f)) { host.send(byteArrayOf(Ansi.ctrl('l'))) }
            Spacer(Modifier.weight(1f))
            // Texto plano en vez del símbolo "⏻" (U+23FB): muchas fuentes de
            // Android no tienen ese glifo y el botón se veía vacío.
            TextButton(onClick = { host.disconnect() }) { Text("Salir") }
        }

        // Toolbar 2: navegación
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KeyButton("↑", Modifier.weight(1f)) { host.send(Ansi.arrow(Ansi.Arrow.UP, host.terminal.cursorKeyApplicationMode)) }
            KeyButton("↓", Modifier.weight(1f)) { host.send(Ansi.arrow(Ansi.Arrow.DOWN, host.terminal.cursorKeyApplicationMode)) }
            KeyButton("←", Modifier.weight(1f)) { host.send(Ansi.arrow(Ansi.Arrow.LEFT, host.terminal.cursorKeyApplicationMode)) }
            KeyButton("→", Modifier.weight(1f)) { host.send(Ansi.arrow(Ansi.Arrow.RIGHT, host.terminal.cursorKeyApplicationMode)) }
            KeyButton("Home", Modifier.weight(1f)) { host.send(Ansi.home(host.terminal.cursorKeyApplicationMode)) }
            KeyButton("End", Modifier.weight(1f)) { host.send(Ansi.end(host.terminal.cursorKeyApplicationMode)) }
            KeyButton("Ctrl+U", Modifier.weight(1f)) { host.send(byteArrayOf(Ansi.ctrl('u'))) }
            KeyButton("Ctrl+W", Modifier.weight(1f)) { host.send(byteArrayOf(Ansi.ctrl('w'))) }
        }

        // Línea de entrada
        Row(
            Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF1E1E1E.toInt()))
                    .padding(8.dp),
                textStyle = TextStyle(
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendLine(host, input); input = "" }),
            )
            TextButton(onClick = { sendLine(host, input); input = "" }) {
                Text("↵")
            }
        }
    }
}

private fun sendLine(host: TerminalHost, text: String) {
    if (text.isNotEmpty()) host.send(text)
    host.send("\r")
}

@Composable
private fun KeyButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(label, fontSize = 12.sp, maxLines = 1, style = MaterialTheme.typography.labelMedium)
    }
}

private fun buildRow(cells: List<Cell>): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var i = 0
    val n = cells.size
    while (i < n) {
        val style = cells[i].style
        val start = i
        while (i < n && cells[i].style == style) i++
        for (k in start until i) builder.append(cells[k].ch.toString())
        builder.addStyle(styleToSpan(style), start, i)
    }
    return builder.toAnnotatedString()
}

private fun styleToSpan(s: com.nxssie.acpssh.terminal.CellStyle): SpanStyle {
    // Resolver fg/bg a colores concretos ANTES de aplicar reverse-video: así
    // "default" se resuelve distinto según el rol (texto claro vs fondo oscuro)
    // y el swap de reverse intercambia colores ya resueltos, no referencias.
    val resolvedFg = colorOf(s.fg, s.bold, isForeground = true)
    val resolvedBg = colorOf(s.bg, bold = false, isForeground = false)
    val fg = if (s.reverse) resolvedBg else resolvedFg
    val bg = if (s.reverse) resolvedFg else resolvedBg
    return SpanStyle(
        color = if (s.faint) fg.copy(alpha = 0.6f) else fg,
        background = bg,
        fontWeight = if (s.bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (s.italic) FontStyle.Italic else FontStyle.Normal,
        textDecoration = if (s.underline) TextDecoration.Underline else null,
    )
}

/** Fondo por defecto del terminal: debe coincidir con el `background()` de [TerminalScreen]. */
private val DefaultTerminalBg = Color(0xFF101010.toInt())

private fun colorOf(ref: ColorRef, bold: Boolean, isForeground: Boolean): Color = when (ref) {
    ColorRef.Default -> when {
        !isForeground -> DefaultTerminalBg
        bold -> Color(0xFFE0E0E0.toInt())
        else -> Color(0xFFD0D0D0.toInt())
    }
    is ColorRef.Palette -> {
        var index = ref.index
        if (bold && isForeground && index in 0..7) index += 8 // negrita = versión brillante
        Color(0xFF000000.toInt() or TerminalColors.rgbFor(index))
    }
    is ColorRef.Rgb -> Color(0xFF000000.toInt() or ref.value)
}
