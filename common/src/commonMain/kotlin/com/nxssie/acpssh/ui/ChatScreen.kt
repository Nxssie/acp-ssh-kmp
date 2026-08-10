package com.nxssie.acpssh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nxssie.acpssh.acp.PermissionOutcome
import com.nxssie.acpssh.acp.ToolCallStatus
import com.nxssie.acpssh.diff.UnifiedDiff
import com.nxssie.acpssh.markdown.Markdown
import com.nxssie.acpssh.session.AcpHost
import com.nxssie.acpssh.session.AcpSessionState
import com.nxssie.acpssh.session.ChatBubble
import com.nxssie.acpssh.session.ChatRole
import com.nxssie.acpssh.session.PlanEntryUi
import com.nxssie.acpssh.session.PermissionUi
import com.nxssie.acpssh.session.ToolCallUi

/** Chat ACP (Fase D/E): burbujas con markdown, tool calls expandibles con diff, plan, permisos. */
@Composable
fun ChatScreen(host: AcpHost) {
    val state by host.session.collectAsState()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    // Autoscroll al último mensaje cuando llega contenido nuevo o streaming.
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        ChatHeader(state, onDisconnect = host::disconnect)

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.plan.isNotEmpty()) {
                item { PlanCard(state.plan) }
            }
            itemsIndexed(state.messages) { _, bubble ->
                Bubble(bubble)
            }
            itemsIndexed(state.toolCalls) { _, tool ->
                ToolCallCard(tool, onToggle = { host.toggleToolCall(tool.id) })
            }
        }

        ChatInput(
            value = input,
            onValueChange = { input = it },
            busy = state.busy,
            onSend = {
                val text = input.trim()
                if (text.isNotEmpty() && !state.busy) {
                    host.sendPrompt(text)
                    input = ""
                }
            },
            onCancel = host::cancelTurn,
        )
    }

    val pending = state.pendingPermission
    if (pending != null) {
        PermissionDialog(pending, host)
    }
}

@Composable
private fun ChatHeader(state: AcpSessionState, onDisconnect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                state.agentName ?: "Agente ACP",
                style = MaterialTheme.typography.titleMedium,
            )
            state.sessionId?.let {
                Text(
                    "Sesión ${it.take(8)}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onDisconnect) { Text("⏻") }
    }
}

@Composable
private fun Bubble(bubble: ChatBubble) {
    val isUser = bubble.role == ChatRole.USER
    val isThought = bubble.role == ChatRole.THOUGHT
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = MaterialTheme.shapes.medium,
            color = when {
                isUser -> MaterialTheme.colorScheme.primaryContainer
                isThought -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.surfaceContainer
            },
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                if (isThought) {
                    Text("💭", style = MaterialTheme.typography.labelMedium)
                }
                val blocks = remember(bubble.text) { Markdown.parse(bubble.text) }
                MarkdownBlocks(blocks, bubble.streaming)
            }
        }
    }
}

@Composable
private fun MarkdownBlocks(blocks: List<Markdown.Block>, streaming: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Markdown.Block.Heading -> Text(
                    Markdown.inlineText(block.inline),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                )
                is Markdown.Block.Paragraph -> InlineText(block.inline, streaming)
                is Markdown.Block.CodeBlock -> CodeBlock(block)
                is Markdown.Block.ListBlock -> Column {
                    block.items.forEachIndexed { index, item ->
                        Row {
                            Text(
                                if (block.ordered) "${index + 1}." else "•",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.width(6.dp))
                            InlineText(item, streaming = false)
                        }
                    }
                }
                is Markdown.Block.Quote -> Text(
                    Markdown.inlineText(block.inline),
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Markdown.Block.ThematicBreak -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        if (streaming) {
            Text("▌", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun InlineText(inline: List<Markdown.Inline>, streaming: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    Text(
        buildAnnotatedString {
            inline.forEach { appendInline(this, it, primary) }
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}

private fun appendInline(
    builder: androidx.compose.ui.text.AnnotatedString.Builder,
    inline: Markdown.Inline,
    primary: Color,
) {
    when (inline) {
        is Markdown.Inline.Text -> builder.append(inline.text)
        is Markdown.Inline.Code -> builder.withStyle(
            SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = Color(0x33000000.toInt()),
                fontSize = 12.sp,
            ),
        ) { append(inline.text) }
        is Markdown.Inline.Bold -> builder.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            inline.children.forEach { appendInline(builder, it, primary) }
        }
        is Markdown.Inline.Italic -> builder.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            inline.children.forEach { appendInline(builder, it, primary) }
        }
        is Markdown.Inline.Link -> builder.withStyle(SpanStyle(color = primary)) {
            append(inline.text)
        }
    }
}

@Composable
private fun CodeBlock(block: Markdown.Block.CodeBlock) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0x1A000000.toInt()))
            .padding(8.dp),
    ) {
        block.language?.takeIf { it.isNotEmpty() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            block.code,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun PlanCard(plan: List<PlanEntryUi>) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Plan", style = MaterialTheme.typography.labelLarge)
            plan.forEach { entry ->
                Row {
                    Text(com.nxssie.acpssh.acp.PlanEntryStatus.icon(entry.status), fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        entry.content,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (entry.status == "in_progress") FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCallCard(tool: ToolCallUi, onToggle: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(com.nxssie.acpssh.acp.ToolKind.icon(tool.kind), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(6.dp))
                Text(
                    tool.title.ifEmpty { tool.id },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    ToolCallStatus.label(tool.status),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor(tool.status),
                )
            }
            if (tool.expanded) {
                tool.input?.let {
                    Text("Entrada", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 12)
                }
                tool.diffs.forEach { diff ->
                    DiffView(diff.path, diff.oldText, diff.newText)
                }
                tool.output?.let {
                    Text("Salida", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 12)
                }
            }
        }
    }
}

@Composable
private fun statusColor(status: String?): Color = when (status) {
    ToolCallStatus.COMPLETED -> Color(0xFF2E7D32.toInt())
    ToolCallStatus.FAILED -> MaterialTheme.colorScheme.error
    ToolCallStatus.IN_PROGRESS -> Color(0xFF1565C0.toInt())
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun DiffView(path: String, oldText: String?, newText: String) {
    val lines = remember(path, oldText, newText) { UnifiedDiff.diff(oldText, newText, path) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF101010.toInt()))
            .padding(6.dp),
    ) {
        lines.forEach { line ->
            val color = when (line.kind) {
                UnifiedDiff.Kind.ADD -> Color(0xFF7CD47C.toInt())
                UnifiedDiff.Kind.DELETE -> Color(0xFFF27979.toInt())
                UnifiedDiff.Kind.HEADER -> Color(0xFF6BA7E8.toInt())
                UnifiedDiff.Kind.CONTEXT -> Color(0xFFCCCCCC.toInt())
            }
            Text(
                line.text,
                color = color,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun PermissionDialog(pending: PermissionUi, host: AcpHost) {
    AlertDialog(
        onDismissRequest = {
            host.respondPermission(pending.request, PermissionOutcome.Cancelled)
        },
        title = { Text("Permiso requerido") },
        text = {
            Column {
                Text(pending.title, fontWeight = FontWeight.SemiBold)
                if (pending.request.toolCall.rawInput != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        com.nxssie.acpssh.acp.AcpPrettyJson.encodeToString(
                            kotlinx.serialization.json.JsonElement.serializer(),
                            pending.request.toolCall.rawInput!!,
                        ),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        maxLines = 12,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("El agente pide permiso para continuar:", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Column {
                pending.request.options.forEach { option ->
                    Button(
                        onClick = { host.respondPermission(pending.request, PermissionOutcome.Selected(option.optionId)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    ) {
                        Text(option.name)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                host.respondPermission(pending.request, PermissionOutcome.Cancelled)
            }) {
                Text("Cancelar")
            }
        },
    )
}

@Composable
private fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (busy) {
            OutlinedButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text("■")
            }
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .heightIn(min = 40.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            enabled = !busy,
        )
        Button(onClick = onSend, enabled = !busy) {
            Text("Enviar")
        }
    }
}
