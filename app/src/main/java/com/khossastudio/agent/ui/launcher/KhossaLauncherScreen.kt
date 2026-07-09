package com.khossastudio.agent.ui.launcher

import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.khossastudio.agent.memory.MemoryManager
import com.khossastudio.agent.skills.Skill
import com.khossastudio.agent.skills.SkillManager
import com.khossastudio.agent.smart.alarms.IntelligentAlarmSystem
import com.khossastudio.agent.smart.controls.SmartControlCenter
import com.khossastudio.agent.smart.personality.PersonalityEngine
import com.khossastudio.agent.ui.compose.KhossaTheme
import com.khossastudio.agent.voice.VoiceInteractionManager

// ==================== ESTADO DO LAUNCHER ====================

data class LauncherUiState(
    val khossaEnabled: Boolean = true,
    val voiceEnabled: Boolean = false,
    val wakeWordEnabled: Boolean = false,
    val automationEnabled: Boolean = false,
    val safeModeEnabled: Boolean = true,
    val statusText: String = "Olá, estou pronto",
    val listening: Boolean = false,
    val thinking: Boolean = false,
    val voiceLevel: Float = 0f,
    val accessibilityActive: Boolean = false,
    val backgroundServiceActive: Boolean = false,
    val mood: String = "PROFESSIONAL",
    val recentApps: List<AppInfo> = emptyList(),
    val quickActions: List<QuickAction> = QuickAction.defaults,
    val skills: List<Skill> = emptyList(),
    val memories: List<MemoryItem> = emptyList(),
    val controls: DeviceControlState = DeviceControlState(),
    val alarms: List<AlarmItem> = emptyList()
)

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable?
)

data class QuickAction(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val description: String,
    val action: QuickActionType
) {
    companion object {
        val defaults = listOf(
            QuickAction("apps", "Apps", Icons.Filled.Apps, "Abrir aplicativos", QuickActionType.OPEN_APPS),
            QuickAction("automation", "Automação", Icons.Default.AutoAwesome, "Gerenciar automações", QuickActionType.AUTOMATION),
            QuickAction("memory", "Memória", Icons.Default.Memory, "Ver memórias", QuickActionType.MEMORY),
            QuickAction("skills", "Skills", Icons.Default.Extension, "Skills instaladas", QuickActionType.SKILLS),
            QuickAction("settings", "Config", Icons.Default.Settings, "Configurações", QuickActionType.SETTINGS),
            QuickAction("voice", "Voz", Icons.Default.Mic, "Assistente de voz", QuickActionType.VOICE)
        )
    }
}

enum class QuickActionType {
    OPEN_APPS, AUTOMATION, MEMORY, SKILLS, SETTINGS, VOICE, RUN_COMMAND, CLEAR_MEMORY
}

data class MemoryItem(
    val key: String,
    val value: String,
    val timestamp: Long
)

data class DeviceControlState(
    val wifi: Boolean = false,
    val bluetooth: Boolean = false,
    val flashlight: Boolean = false,
    val location: Boolean = false,
    val dnd: Boolean = false,
    val powerSave: Boolean = false
)

data class AlarmItem(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val label: String,
    val enabled: Boolean,
    val repeatType: String
)

// ==================== LAUNCHER SCREEN PRINCIPAL ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KhossaLauncherScreen(
    state: LauncherUiState,
    prefs: SharedPreferences,
    skillManager: SkillManager,
    memoryManager: MemoryManager,
    voiceManager: VoiceInteractionManager,
    controlCenter: SmartControlCenter,
    alarmSystem: IntelligentAlarmSystem,
    personalityEngine: PersonalityEngine,
    onToggleKhossa: () -> Unit,
    onToggleVoice: () -> Unit,
    onToggleWakeWord: () -> Unit,
    onToggleAutomation: () -> Unit,
    onToggleSafeMode: () -> Unit,
    onMicClick: () -> Unit,
    onQuickAction: (QuickAction) -> Unit,
    onOpenApp: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSkills: () -> Unit,
    onNavigateToMemory: () -> Unit,
    onNavigateToAutomation: () -> Unit,
    onNavigateToApps: () -> Unit,
    onNavigateToVoice: () -> Unit,
    onNavigateToAlarms: () -> Unit
) {
    var currentTab by remember { mutableIntStateOf(0) }
    
    // Inicializar voice manager de forma segura
    LaunchedEffect(Unit) {
        try {
            voiceManager.init()
        } catch (e: Exception) {
            // TTS pode não estar disponível em alguns dispositivos
        }
    }
    
    KhossaTheme {
        Scaffold(
            containerColor = Color(0xFF0D1117),
            bottomBar = {
                LauncherBottomBar(
                    currentTab = currentTab,
                    onTabChange = { currentTab = it }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                when (currentTab) {
                    0 -> HomeTab(
                        state = state,
                        onToggleKhossa = onToggleKhossa,
                        onToggleVoice = onToggleVoice,
                        onToggleWakeWord = onToggleWakeWord,
                        onToggleAutomation = onToggleAutomation,
                        onToggleSafeMode = onToggleSafeMode,
                        onMicClick = onMicClick,
                        onQuickAction = onQuickAction,
                        onOpenApp = onOpenApp,
                        onNavigateToSettings = onNavigateToSettings,
                        onNavigateToVoice = onNavigateToVoice
                    )
                    1 -> AppsTab(
                        state = state,
                        onOpenApp = onOpenApp,
                        onNavigateToApps = onNavigateToApps
                    )
                    2 -> ControlsTab(
                        state = state,
                        onNavigateToAlarms = onNavigateToAlarms
                    )
                    3 -> SettingsTab(
                        state = state,
                        onToggleKhossa = onToggleKhossa,
                        onToggleVoice = onToggleVoice,
                        onToggleWakeWord = onToggleWakeWord,
                        onToggleAutomation = onToggleAutomation,
                        onToggleSafeMode = onToggleSafeMode,
                        onNavigateToSettings = onNavigateToSettings,
                        onNavigateToSkills = onNavigateToSkills,
                        onNavigateToMemory = onNavigateToMemory,
                        onNavigateToAutomation = onNavigateToAutomation
                    )
                }
            }
        }
    }
}

// ==================== HOME TAB ====================

@Composable
private fun HomeTab(
    state: LauncherUiState,
    onToggleKhossa: () -> Unit,
    onToggleVoice: () -> Unit,
    onToggleWakeWord: () -> Unit,
    onToggleAutomation: () -> Unit,
    onToggleSafeMode: () -> Unit,
    onMicClick: () -> Unit,
    onQuickAction: (QuickAction) -> Unit,
    onOpenApp: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToVoice: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header com status
        LauncherHeader(
            khossaEnabled = state.khossaEnabled,
            statusText = state.statusText,
            onSettingsClick = onNavigateToSettings
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Orbe de voz
        VoiceOrb(
            listening = state.listening,
            thinking = state.thinking,
            level = state.voiceLevel,
            onClick = onMicClick
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Status badges
        StatusBadges(
            voiceEnabled = state.voiceEnabled,
            wakeWordEnabled = state.wakeWordEnabled,
            automationEnabled = state.automationEnabled,
            safeModeEnabled = state.safeModeEnabled
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Quick Actions Grid
        QuickActionsGrid(
            actions = state.quickActions,
            onAction = onQuickAction
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Aplicativos recentes
        if (state.recentApps.isNotEmpty()) {
            RecentAppsSection(
                apps = state.recentApps,
                onOpenApp = onOpenApp
            )
        }
        
        Spacer(modifier = Modifier.height(80.dp))
    }
}

// ==================== APPS TAB ====================

@Composable
private fun AppsTab(
    state: LauncherUiState,
    onOpenApp: (String) -> Unit,
    onNavigateToApps: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            "Aplicativos",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            "Apps mais usados",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        if (state.recentApps.isEmpty()) {
            EmptyAppsPlaceholder(onNavigateToApps = onNavigateToApps)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(count = state.recentApps.size) { index ->
                    val app = state.recentApps[index]
                    AppIcon(
                        name = app.name,
                        icon = app.icon,
                        onClick = { onOpenApp(app.packageName) }
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Botão ver todos
        OutlinedButton(
            onClick = onNavigateToApps,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Apps, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Ver todos os apps")
        }
    }
}

// ==================== CONTROLS TAB ====================

@Composable
private fun ControlsTab(
    state: LauncherUiState,
    onNavigateToAlarms: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            "Controles",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Device Controls Grid
        DeviceControlsGrid(
            controls = state.controls
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Alarmes
        AlarmesSection(
            alarms = state.alarms,
            onNavigateToAlarms = onNavigateToAlarms
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Voice Actions
        VoiceActionsSection(onNavigateToVoice = onNavigateToAlarms)
    }
}

// ==================== SETTINGS TAB ====================

@Composable
private fun SettingsTab(
    state: LauncherUiState,
    onToggleKhossa: () -> Unit,
    onToggleVoice: () -> Unit,
    onToggleWakeWord: () -> Unit,
    onToggleAutomation: () -> Unit,
    onToggleSafeMode: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSkills: () -> Unit,
    onNavigateToMemory: () -> Unit,
    onNavigateToAutomation: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            "Configurações",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Agent Toggles
        AgentTogglesSection(
            khossaEnabled = state.khossaEnabled,
            voiceEnabled = state.voiceEnabled,
            wakeWordEnabled = state.wakeWordEnabled,
            automationEnabled = state.automationEnabled,
            safeModeEnabled = state.safeModeEnabled,
            onToggleKhossa = onToggleKhossa,
            onToggleVoice = onToggleVoice,
            onToggleWakeWord = onToggleWakeWord,
            onToggleAutomation = onToggleAutomation,
            onToggleSafeMode = onToggleSafeMode
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Quick Settings Links
        SettingsLinksSection(
            onNavigateToSettings = onNavigateToSettings,
            onNavigateToSkills = onNavigateToSkills,
            onNavigateToMemory = onNavigateToMemory,
            onNavigateToAutomation = onNavigateToAutomation
        )
    }
}

// ==================== COMPONENTES REUTILIZÁVEIS ====================

@Composable
private fun LauncherHeader(
    khossaEnabled: Boolean,
    statusText: String,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (khossaEnabled) Color(0xFF4CAF50) else Color(0xFFF44336))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Khossa",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )
        }
        
        IconButton(onClick = onSettingsClick) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "Configurações",
                tint = Color.White
            )
        }
    }
}

@Composable
private fun VoiceOrb(
    listening: Boolean,
    thinking: Boolean,
    level: Float,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")
    
    val breath by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )
    
    Box(
        modifier = Modifier
            .size((160 * breath).dp)
            .clip(CircleShape)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF6366F1),
                        Color(0xFF8B5CF6),
                        Color(0xFF06B6D4)
                    )
                )
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (thinking) {
            CircularProgressIndicator(
                modifier = Modifier.size(80.dp),
                color = Color.White.copy(alpha = 0.8f),
                strokeWidth = 3.dp
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = "Microfone",
                tint = Color.White,
                modifier = Modifier.size(48.dp)
            )
        }
    }
}

@Composable
private fun StatusBadges(
    voiceEnabled: Boolean,
    wakeWordEnabled: Boolean,
    automationEnabled: Boolean,
    safeModeEnabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        StatusBadge("IA", voiceEnabled, Icons.Default.Psychology)
        Spacer(modifier = Modifier.width(12.dp))
        StatusBadge("Voz", voiceEnabled, Icons.Default.Mic)
        Spacer(modifier = Modifier.width(12.dp))
        StatusBadge("Auto", automationEnabled, Icons.Default.AutoAwesome)
        Spacer(modifier = Modifier.width(12.dp))
        StatusBadge("Seguro", safeModeEnabled, Icons.Default.Security)
    }
}

@Composable
private fun StatusBadge(label: String, active: Boolean, icon: ImageVector) {
    Surface(
        color = if (active) Color(0xFF4CAF50).copy(alpha = 0.2f) else Color.Gray.copy(alpha = 0.1f),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (active) Color(0xFF4CAF50) else Color.Gray,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                label,
                color = if (active) Color(0xFF4CAF50) else Color.Gray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun QuickActionsGrid(
    actions: List<QuickAction>,
    onAction: (QuickAction) -> Unit
) {
    Column {
        Text(
            "Ações Rápidas",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(count = actions.size) { index ->
                val action = actions[index]
                QuickActionCard(action = action, onClick = { onAction(action) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickActionCard(action: QuickAction, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1C2128)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                action.icon,
                contentDescription = action.title,
                tint = Color(0xFF6366F1),
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                action.title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun RecentAppsSection(
    apps: List<AppInfo>,
    onOpenApp: (String) -> Unit
) {
    Column {
        Text(
            "Usados Recentemente",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(apps) { app ->
                RecentAppItem(app = app, onClick = { onOpenApp(app.packageName) })
            }
        }
    }
}

@Composable
private fun RecentAppItem(app: AppInfo, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Surface(
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF1C2128)
        ) {
            Box(contentAlignment = Alignment.Center) {
                app.icon?.let {
                    Image(
                        bitmap = it.toBitmap().asImageBitmap(),
                        contentDescription = app.name,
                        modifier = Modifier.size(40.dp)
                    )
                } ?: Icon(
                    Icons.Default.Apps,
                    contentDescription = app.name,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            app.name,
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EmptyAppsPlaceholder(onNavigateToApps: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.Apps,
            contentDescription = null,
            tint = Color.Gray,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Nenhum app detectado ainda",
            color = Color.Gray,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onNavigateToApps) {
            Text("Ver todos os apps")
        }
    }
}

@Composable
private fun DeviceControlsGrid(controls: DeviceControlState) {
    Column {
        Text(
            "Controle do Dispositivo",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ControlCard("Wi-Fi", Icons.Default.Wifi, controls.wifi, Modifier.weight(1f))
            ControlCard("Bluetooth", Icons.Default.Bluetooth, controls.bluetooth, Modifier.weight(1f))
            ControlCard("Lanterna", Icons.Default.FlashlightOn, controls.flashlight, Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ControlCard("Local", Icons.Default.LocationOn, controls.location, Modifier.weight(1f))
            ControlCard("DND", Icons.Default.DoNotDisturb, controls.dnd, Modifier.weight(1f))
            ControlCard("Eco", Icons.Default.BatterySaver, controls.powerSave, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ControlCard(title: String, icon: ImageVector, active: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = if (active) Color(0xFF4CAF50).copy(alpha = 0.2f) else Color(0xFF1C2128),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = title,
                tint = if (active) Color(0xFF4CAF50) else Color.Gray,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                title,
                color = if (active) Color.White else Color.Gray,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun AlarmesSection(alarms: List<AlarmItem>, onNavigateToAlarms: () -> Unit) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Alarmes",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            TextButton(onClick = onNavigateToAlarms) {
                Text("Ver todos", color = Color(0xFF6366F1))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        
        if (alarms.isEmpty()) {
            Surface(
                color = Color(0xFF1C2128),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Alarm, contentDescription = null, tint = Color.Gray)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Nenhum alarme definido", color = Color.Gray)
                }
            }
        } else {
            alarms.forEach { alarm ->
                Surface(
                    color = Color(0xFF1C2128),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            String.format("%02d:%02d", alarm.hour, alarm.minute),
                            color = if (alarm.enabled) Color.White else Color.Gray,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            alarm.label.ifEmpty { "Alarme" },
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceActionsSection(onNavigateToVoice: () -> Unit) {
    Column {
        Text(
            "Ações de Voz",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            VoiceActionCard(
                "Diga algo",
                "Comando de voz",
                Icons.Default.Mic,
                onNavigateToVoice,
                Modifier.weight(1f)
            )
            VoiceActionCard(
                "Automação",
                "Comandos rápidos",
                Icons.Default.AutoAwesome,
                onNavigateToVoice,
                Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C2128)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Icon(icon, contentDescription = null, tint = Color(0xFF6366F1))
            Spacer(modifier = Modifier.height(8.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color.Gray, fontSize = 12.sp)
        }
    }
}

@Composable
private fun AgentTogglesSection(
    khossaEnabled: Boolean,
    voiceEnabled: Boolean,
    wakeWordEnabled: Boolean,
    automationEnabled: Boolean,
    safeModeEnabled: Boolean,
    onToggleKhossa: () -> Unit,
    onToggleVoice: () -> Unit,
    onToggleWakeWord: () -> Unit,
    onToggleAutomation: () -> Unit,
    onToggleSafeMode: () -> Unit
) {
    Column {
        Text(
            "Controles do Agente",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        
        ToggleCard("Khossa Ativado", "Assistente ativo", khossaEnabled, onToggleKhossa)
        ToggleCard("Assistente de Voz", "Reconhecimento de voz", voiceEnabled, onToggleVoice)
        ToggleCard("Wake Word", "Escuta contínua", wakeWordEnabled, onToggleWakeWord)
        ToggleCard("Automação", "Execução automática", automationEnabled, onToggleAutomation)
        ToggleCard("Modo Seguro", "Confirmação para ações", safeModeEnabled, onToggleSafeMode)
    }
}

@Composable
private fun ToggleCard(title: String, subtitle: String, checked: Boolean, onToggle: () -> Unit) {
    Surface(
        color = Color(0xFF1C2128),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(title, color = Color.White, fontWeight = FontWeight.Medium)
                Text(subtitle, color = Color.Gray, fontSize = 12.sp)
            }
            Switch(
                checked = checked,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF4CAF50),
                    checkedTrackColor = Color(0xFF4CAF50).copy(alpha = 0.5f)
                )
            )
        }
    }
}

@Composable
private fun SettingsLinksSection(
    onNavigateToSettings: () -> Unit,
    onNavigateToSkills: () -> Unit,
    onNavigateToMemory: () -> Unit,
    onNavigateToAutomation: () -> Unit
) {
    Column {
        Text(
            "Acesso Rápido",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        
        SettingsLink(Icons.Default.Settings, "Configurações Avançadas", "API, modelo, permissões", onNavigateToSettings)
        SettingsLink(Icons.Default.Extension, "Skills", "Gerenciar skills", onNavigateToSkills)
        SettingsLink(Icons.Default.Memory, "Memória", "Ver e gerenciar memórias", onNavigateToMemory)
        SettingsLink(Icons.Default.AutoAwesome, "Automação", "Regras e rotinas", onNavigateToAutomation)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsLink(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C2128)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = Color(0xFF6366F1))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Medium)
                Text(subtitle, color = Color.Gray, fontSize = 12.sp)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray)
        }
    }
}

@Composable
private fun LauncherBottomBar(currentTab: Int, onTabChange: (Int) -> Unit) {
    NavigationBar(
        containerColor = Color(0xFF161B22)
    ) {
        NavigationBarItem(
            icon = { Icon(Icons.Default.Home, contentDescription = "Início") },
            label = { Text("Início") },
            selected = currentTab == 0,
            onClick = { onTabChange(0) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF6366F1),
                selectedTextColor = Color(0xFF6366F1),
                indicatorColor = Color(0xFF6366F1).copy(alpha = 0.2f)
            )
        )
        NavigationBarItem(
            icon = { Icon(Icons.Filled.Apps, contentDescription = "Apps") },
            label = { Text("Apps") },
            selected = currentTab == 1,
            onClick = { onTabChange(1) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF6366F1),
                selectedTextColor = Color(0xFF6366F1),
                indicatorColor = Color(0xFF6366F1).copy(alpha = 0.2f)
            )
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Dashboard, contentDescription = "Controles") },
            label = { Text("Controles") },
            selected = currentTab == 2,
            onClick = { onTabChange(2) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF6366F1),
                selectedTextColor = Color(0xFF6366F1),
                indicatorColor = Color(0xFF6366F1).copy(alpha = 0.2f)
            )
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Settings, contentDescription = "Config") },
            label = { Text("Config") },
            selected = currentTab == 3,
            onClick = { onTabChange(3) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color(0xFF6366F1),
                selectedTextColor = Color(0xFF6366F1),
                indicatorColor = Color(0xFF6366F1).copy(alpha = 0.2f)
            )
        )
    }
}

@Composable
private fun AppIcon(name: String, icon: Drawable?, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Surface(
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF1C2128)
        ) {
            Box(contentAlignment = Alignment.Center) {
                icon?.let {
                    Image(
                        bitmap = it.toBitmap().asImageBitmap(),
                        contentDescription = name,
                        modifier = Modifier.size(40.dp)
                    )
                } ?: Icon(
                    Icons.Default.Apps,
                    contentDescription = name,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            name,
            color = Color.White,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// Extension function to convert Drawable to Bitmap
private fun Drawable.toBitmap(): android.graphics.Bitmap {
    if (this is android.graphics.drawable.BitmapDrawable) {
        return this.bitmap
    }
    val width = if (intrinsicWidth > 0) intrinsicWidth else 48
    val height = if (intrinsicHeight > 0) intrinsicHeight else 48
    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap
}
