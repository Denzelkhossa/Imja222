package com.khossastudio.agent.ui.compose

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Um item do histórico de conversa mostrado no ecrã do assistente. */
data class ConversationEntry(val fromUser: Boolean, val text: String)

/** Estado do ecrã do assistente. */
data class AssistantUiState(
    val listening: Boolean = false,
    val thinking: Boolean = false,
    val statusText: String = "Toque para falar",
    val history: List<ConversationEntry> = emptyList(),
    val accessibilityActive: Boolean = false,
    val backgroundServiceActive: Boolean = false,
    val voiceLevel: Float = 0f
)

@Composable
fun KhossaAssistantScreen(
    state: AssistantUiState,
    onMicClick: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    onOpenPermissions: () -> Unit,
    onToggleBackgroundService: () -> Unit
) {
    KhossaTheme {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopBar(
                    accessibilityActive = state.accessibilityActive,
                    onOpenAdvancedSettings = onOpenAdvancedSettings,
                    onOpenPermissions = onOpenPermissions
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(16.dp))

                // Histórico de conversas com animação de entrada
                ConversationHistory(
                    history = state.history,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))

                // Status Text com animação de fade
                AnimatedContent(
                    targetState = state.statusText,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                    },
                    label = "status_text"
                ) { targetText ->
                    Text(
                        text = targetText,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp
                    )
                }

                Spacer(Modifier.height(20.dp))

                // Orbe estilo Siri
                SiriOrb(
                    listening = state.listening,
                    thinking = state.thinking,
                    level = state.voiceLevel,
                    onClick = onMicClick
                )

                Spacer(Modifier.height(32.dp))

                // Ações inferiores
                BottomActions(
                    backgroundServiceActive = state.backgroundServiceActive,
                    onToggleBackgroundService = onToggleBackgroundService
                )
                
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun TopBar(
    accessibilityActive: Boolean,
    onOpenAdvancedSettings: () -> Unit,
    onOpenPermissions: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Khossa",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.ExtraBold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (accessibilityActive) Color(0xFF4CAF50) else Color(0xFFF44336))
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (accessibilityActive) "Serviço Ativo" else "Serviço Inativo",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (accessibilityActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        }
        Row {
            IconButton(
                onClick = onOpenPermissions,
                modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surface)
            ) {
                Icon(Icons.Filled.Notifications, contentDescription = "Permissões", tint = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onOpenAdvancedSettings,
                modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surface)
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "Configurações", tint = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
}

@Composable
private fun SiriOrb(
    listening: Boolean,
    thinking: Boolean,
    level: Float,
    onClick: () -> Unit
) {
    val infinite = rememberInfiniteTransition(label = "siri_orb")
    val active = listening || thinking

    val huePhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(if (active) 3000 else 8000, easing = LinearEasing)),
        label = "hue_phase"
    )

    val idleBreath by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (active) 600 else 2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idle_breath"
    )

    val voiceEnergy = if (listening) level else 0f
    val energy = idleBreath * (if (thinking) 0.2f else 0.1f) + voiceEnergy * 0.6f
    val scale by animateFloatAsState(targetValue = 1f + energy, animationSpec = tween(100), label = "orb_scale")

    val waveLevel = if (listening) (0.15f + level * 0.85f) else idleBreath * 0.15f

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(240.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val orbCenter = center
            val baseRadius = 50.dp.toPx()
            val orbRadius = baseRadius * scale

            fun colorAt(phase: Float): Color {
                val p = ((phase % 1f) + 1f) % 1f
                val scaled = p * (SiriPalette.Ring.size - 1)
                val i = scaled.toInt().coerceIn(0, SiriPalette.Ring.size - 2)
                return lerp(SiriPalette.Ring[i], SiriPalette.Ring[i + 1], scaled - i)
            }

            // Ondulação externa
            for (i in 3 downTo 1) {
                val ringRadius = baseRadius + i * 15.dp.toPx() + waveLevel * i * 20.dp.toPx()
                val ringAlpha = (waveLevel * (0.6f - i * 0.15f)).coerceIn(0f, 0.5f)
                drawCircle(
                    color = colorAt(huePhase + i * 0.15f).copy(alpha = ringAlpha),
                    radius = ringRadius,
                    center = orbCenter,
                    style = Stroke(width = (4 - i).dp.toPx())
                )
            }

            // Núcleo
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(colorAt(huePhase), colorAt(huePhase + 0.3f), colorAt(huePhase + 0.6f)),
                    center = orbCenter,
                    radius = orbRadius
                ),
                radius = orbRadius,
                center = orbCenter
            )

            // Brilho
            val highlightCenter = Offset(orbCenter.x - orbRadius * 0.35f, orbCenter.y - orbRadius * 0.35f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(SiriPalette.Highlight.copy(alpha = 0.9f), Color.Transparent),
                    center = highlightCenter,
                    radius = orbRadius * 0.8f
                ),
                radius = orbRadius * 0.8f,
                center = highlightCenter
            )
        }

        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick)
        )
    }
}

@Composable
private fun ConversationHistory(history: List<ConversationEntry>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(history.size) {
        if (history.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(history.size - 1)
            }
        }
    }

    if (history.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                "Como posso ajudar hoje?",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
            )
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(history) { entry ->
                ConversationBubble(entry)
            }
        }
    }
}

@Composable
private fun ConversationBubble(entry: ConversationEntry) {
    val alignment = if (entry.fromUser) Alignment.End else Alignment.Start
    val backgroundColor = if (entry.fromUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
    else MaterialTheme.colorScheme.surface
    val textColor = if (entry.fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val shape = if (entry.fromUser) {
        RoundedCornerShape(18.dp, 18.dp, 2.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 2.dp)
    }

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Surface(
            color = backgroundColor,
            shape = shape,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .shadow(if (entry.fromUser) 4.dp else 1.dp, shape)
        ) {
            Text(
                text = entry.text,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun BottomActions(backgroundServiceActive: Boolean, onToggleBackgroundService: () -> Unit) {
    Surface(
        onClick = onToggleBackgroundService,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(20.dp))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(if (backgroundServiceActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Gray.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.PowerSettingsNew,
                        contentDescription = null,
                        tint = if (backgroundServiceActive) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (backgroundServiceActive) "Escuta Ativa" else "Escuta Inativa",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Diga \"Olá, Khossa\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            Switch(
                checked = backgroundServiceActive,
                onCheckedChange = { onToggleBackgroundService() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
            )
        }
    }
}
