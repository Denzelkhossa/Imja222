package com.khossastudio.agent.smart.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.khossastudio.agent.smart.controls.SmartControlCenter
import com.khossastudio.agent.smart.controls.SmartControlCenter.ControlStatus
import com.khossastudio.agent.smart.alarms.IntelligentAlarmSystem
import com.khossastudio.agent.smart.personality.PersonalityEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartDashboard(
    controlCenter: SmartControlCenter,
    alarmSystem: IntelligentAlarmSystem,
    personalityEngine: PersonalityEngine,
    onNavigateToVoice: () -> Unit,
    onNavigateToApps: () -> Unit,
    onNavigateToAlarms: () -> Unit
) {
    val controls by controlCenter.controlsState.collectAsState()
    val alarms by alarmSystem.alarms.collectAsState()
    val mood by personalityEngine.mood.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🎯 Painel Inteligente", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Mood Indicator
            item {
                MoodCard(mood = mood.name)
            }
            
            // Quick Voice Action
            item {
                VoiceActionCard(onClick = onNavigateToVoice)
            }
            
            // Smart Controls Grid
            item {
                Text("⚡ Controle Rápido", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))
                ControlsGrid(controls = controls, onToggle = { /* handle toggle */ })
            }
            
            // Recent Alarms
            item {
                AlarmsSection(alarms = alarms, onClick = onNavigateToAlarms)
            }
            
            // Quick Actions
            item {
                QuickActionsRow(onNavigateToApps = onNavigateToApps)
            }
        }
    }
}

@Composable
fun MoodCard(mood: String) {
    val (icon, color, description) = when (mood) {
        "PROFESSIONAL" -> Triple(Icons.Default.Work, Color(0xFF1565C0), "Modo Profissional")
        "FRIENDLY" -> Triple(Icons.Default.Favorite, Color(0xFFE91E63), "Modo Amigável")
        "ENERGETIC" -> Triple(Icons.Default.Bolt, Color(0xFFFF9800), "Modo Energético")
        "CALM" -> Triple(Icons.Default.SelfImprovement, Color(0xFF4CAF50), "Modo Calmo")
        "CURIOUS" -> Triple(Icons.Default.QuestionMark, Color(0xFF9C27B0), "Modo Curioso")
        else -> Triple(Icons.Default.Psychology, Color(0xFF607D8B), "Modo Padrão")
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("Estado Atual", fontSize = 12.sp, color = Color.Gray)
                Text(description, fontWeight = FontWeight.Bold, color = color)
            }
        }
    }
}

@Composable
fun VoiceActionCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Mic,
                    contentDescription = "Falar",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text("Diga algo", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("Toque ou diga \"Ok Khossa\"", fontSize = 14.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
fun ControlsGrid(
    controls: SmartControlCenter.DeviceControlsState,
    onToggle: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ControlCard(
                icon = Icons.Default.Wifi,
                title = "Wi-Fi",
                status = controls.wifi,
                modifier = Modifier.weight(1f),
                onClick = { onToggle("wifi") }
            )
            ControlCard(
                icon = Icons.Default.Bluetooth,
                title = "Bluetooth",
                status = controls.bluetooth,
                modifier = Modifier.weight(1f),
                onClick = { onToggle("bluetooth") }
            )
            ControlCard(
                icon = Icons.Default.FlashlightOn,
                title = "Lanterna",
                status = controls.flashlight,
                modifier = Modifier.weight(1f),
                onClick = { onToggle("flashlight") }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ControlCard(
                icon = Icons.Default.LocationOn,
                title = "Local",
                status = controls.location,
                modifier = Modifier.weight(1f),
                onClick = { onToggle("location") }
            )
            ControlCard(
                icon = Icons.Default.VolumeUp,
                title = "Volume",
                status = if (controls.volumeLevel > 0) ControlStatus.ON else ControlStatus.OFF,
                modifier = Modifier.weight(1f),
                onClick = { onToggle("volume") }
            )
            ControlCard(
                icon = Icons.Default.Brightness6,
                title = "Brilho",
                status = if (controls.brightnessLevel > 50) ControlStatus.ON else ControlStatus.OFF,
                modifier = Modifier.weight(1f),
                onClick = { onToggle("brightness") }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ControlCard(
                icon = Icons.Default.DoNotDisturb,
                title = "DND",
                status = controls.doNotDisturb,
                modifier = Modifier.weight(1f),
                onClick = { onToggle("dnd") }
            )
            ControlCard(
                icon = Icons.Default.BatterySaver,
                title = "Economia",
                status = controls.powerSave,
                modifier = Modifier.weight(1f),
                onClick = { onToggle("powersave") }
            )
            ControlCard(
                icon = Icons.Default.ScreenRotation,
                title = "Rotação",
                status = controls.screenRotation,
                modifier = Modifier.weight(1f),
                onClick = { onToggle("rotation") }
            )
        }
    }
}

@Composable
fun ControlCard(
    icon: ImageVector,
    title: String,
    status: ControlStatus,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val (bgColor, iconColor) = when (status) {
        ControlStatus.ON -> Pair(Color(0xFF4CAF50).copy(alpha = 0.2f), Color(0xFF4CAF50))
        ControlStatus.OFF -> Pair(Color.Gray.copy(alpha = 0.1f), Color.Gray)
        ControlStatus.UNKNOWN -> Pair(Color(0xFFFF9800).copy(alpha = 0.1f), Color(0xFFFF9800))
    }
    
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = title, tint = iconColor, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun AlarmsSection(
    alarms: List<IntelligentAlarmSystem.SmartAlarm>,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Alarm, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Alarmes", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.weight(1f))
                Text("Ver todos →", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (alarms.isEmpty()) {
                Text("Nenhum alarme definido", color = Color.Gray, fontSize = 14.sp)
            } else {
                alarms.take(3).forEach { alarm ->
                    AlarmItem(alarm = alarm)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun AlarmItem(alarm: IntelligentAlarmSystem.SmartAlarm) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            String.format("%02d:%02d", alarm.hour, alarm.minute),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = if (alarm.enabled) MaterialTheme.colorScheme.primary else Color.Gray
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            if (alarm.label.isNotEmpty()) {
                Text(alarm.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            val repeatText = when (alarm.repeatType) {
                IntelligentAlarmSystem.RepeatType.DAILY -> "Todos os dias"
                IntelligentAlarmSystem.RepeatType.WEEKDAYS -> "Dias úteis"
                IntelligentAlarmSystem.RepeatType.WEEKENDS -> "Fins de semana"
                else -> "Uma vez"
            }
            Text(repeatText, fontSize = 12.sp, color = Color.Gray)
        }
    }
}

@Composable
fun QuickActionsRow(onNavigateToApps: () -> Unit) {
    Column {
        Text("⚡ Ações Rápidas", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                QuickActionChip(
                    icon = Icons.Default.Apps,
                    label = "Apps",
                    onClick = onNavigateToApps
                )
            }
            item {
                QuickActionChip(
                    icon = Icons.Default.Timer,
                    label = "Temporizador",
                    onClick = { }
                )
            }
            item {
                QuickActionChip(
                    icon = Icons.Default.PlayCircle,
                    label = "Música",
                    onClick = { }
                )
            }
            item {
                QuickActionChip(
                    icon = Icons.Default.Map,
                    label = "Navegação",
                    onClick = { }
                )
            }
            item {
                QuickActionChip(
                    icon = Icons.Default.Settings,
                    label = "Config",
                    onClick = { }
                )
            }
        }
    }
}

@Composable
fun QuickActionChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, fontSize = 14.sp)
        }
    }
}
