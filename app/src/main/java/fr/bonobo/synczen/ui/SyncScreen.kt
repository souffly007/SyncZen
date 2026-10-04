package fr.bonobo.synczen.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import fr.bonobo.synczen.R
import fr.bonobo.synczen.data.*
import fr.bonobo.synczen.ui.theme.SyncThemeMode
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(viewModel: SyncViewModel, themeMode: SyncThemeMode, onThemeChanged: (SyncThemeMode) -> Unit) {
    val context = LocalContext.current
    val savedConfig by viewModel.config.collectAsState()
    val logs by viewModel.recentLogs.collectAsState()
    val testResult by viewModel.testResult.collectAsState()
    val saveResult by viewModel.saveResult.collectAsState()
    var serverType by remember { mutableStateOf(savedConfig?.serverType ?: ServerType.SMB) }
    var address by remember { mutableStateOf(savedConfig?.address ?: "") }
    var username by remember { mutableStateOf(savedConfig?.username ?: "") }
    var password by remember { mutableStateOf(savedConfig?.password ?: "") }
    var requireCharging by remember { mutableStateOf(savedConfig?.requireCharging ?: false) }
    var hasEditedFields by remember { mutableStateOf(false) }

    // Room peut répondre après le premier affichage Compose : on remet alors
    // les champs à jour avec la configuration réellement enregistrée.
    LaunchedEffect(savedConfig) {
        savedConfig?.let { config ->
            if (hasEditedFields) return@let
            serverType = config.serverType
            address = config.address
            username = config.username
            password = config.password
            requireCharging = config.requireCharging
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            viewModel.addSourceFolder(uri.toString())
        }
    }
    fun currentConfig() = NasConfig(serverType = serverType, address = address, username = username, password = password, sourceFolderUris = savedConfig?.sourceFolderUris ?: "", requireCharging = requireCharging, lastSyncTimestamp = savedConfig?.lastSyncTimestamp ?: 0L)

    Scaffold(topBar = {
        TopAppBar(title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Image(painter = painterResource(R.drawable.synczen_server_hero), contentDescription = "SyncZen", modifier = Modifier.size(38.dp), contentScale = ContentScale.Fit)
                }
                Spacer(Modifier.size(10.dp))
                Column { Text("SyncZen", fontWeight = FontWeight.Bold); Text("Votre serveur, toujours à jour", style = MaterialTheme.typography.labelSmall) }
            }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(26.dp)) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 214.dp)) {
                        Image(
                            painter = painterResource(R.drawable.synczen_server_hero), contentDescription = null,
                            modifier = Modifier.align(Alignment.TopEnd).size(190.dp).offset(x = 28.dp, y = (-12).dp).alpha(.26f), contentScale = ContentScale.Fit
                        )
                        Column(Modifier.fillMaxWidth().padding(22.dp)) {
                            Text("SYNCHRONISATION", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .72f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(7.dp))
                            Text("Tout reste\nà votre portée.", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(9.dp))
                            Text("Vos dossiers sont envoyés vers votre serveur en toute simplicité.", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .78f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth(.68f))
                            Spacer(Modifier.height(18.dp))
                            Button(onClick = { viewModel.forceSyncNow() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onPrimary, contentColor = MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth()) { Text("↻  Synchroniser maintenant", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
            item { SectionTitle("Destination", "Où envoyer vos sauvegardes ?") }
            item {
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        var expanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                            OutlinedTextField(value = serverType.name, onValueChange = {}, readOnly = true, label = { Text("Protocole") }, modifier = Modifier.fillMaxWidth().menuAnchor())
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { ServerType.values().forEach { type -> DropdownMenuItem(text = { Text(type.name) }, onClick = { serverType = type; expanded = false }) } }
                        }
                        OutlinedTextField(value = address, onValueChange = { address = it; hasEditedFields = true }, label = { Text("Adresse du serveur") }, placeholder = { Text("smb://mon-nas/partage") }, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(value = username, onValueChange = { username = it; hasEditedFields = true }, label = { Text("Utilisateur") }, modifier = Modifier.weight(1f))
                            OutlinedTextField(value = password, onValueChange = { password = it; hasEditedFields = true }, label = { Text("Mot de passe") }, modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { viewModel.testConnection(currentConfig()) }, modifier = Modifier.weight(1f)) { Text("Tester") }
                            Button(onClick = { viewModel.saveConfig(currentConfig()) }, modifier = Modifier.weight(1f)) { Text("Enregistrer") }
                        }
                        saveResult?.let { Text(it, color = if (it.contains("obligatoire")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                        testResult?.let { Text(it, color = if (it.startsWith("Échec")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item { SectionTitle("Dossiers sources", "Les éléments à protéger") }
            item {
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val folders = savedConfig?.sourceFolderUriList().orEmpty()
                        if (folders.isEmpty()) Text("Aucun dossier sélectionné", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
                        folders.forEach { uriString ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("▣", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
                                Text(DocumentFile.fromTreeUri(context, android.net.Uri.parse(uriString))?.name ?: uriString, Modifier.weight(1f).padding(start = 10.dp))
                                IconButton(onClick = { viewModel.removeSourceFolder(uriString) }) { Text("×", style = MaterialTheme.typography.titleLarge) }
                            }
                        }
                        OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("＋  Ajouter un dossier") }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = requireCharging, onCheckedChange = { requireCharging = it }); Text("Synchroniser uniquement pendant la charge") }
                    }
                }
            }
            item { SectionTitle("Apparence", "Choisissez votre ambiance") }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChoice("Clair", "☀", SyncThemeMode.LIGHT, themeMode, onThemeChanged, Modifier.weight(1f))
                    ThemeChoice("Sombre", "☾", SyncThemeMode.DARK, themeMode, onThemeChanged, Modifier.weight(1f))
                    ThemeChoice("CyanogenMOD", "◈", SyncThemeMode.CYANOGEN, themeMode, onThemeChanged, Modifier.weight(1f))
                }
            }
            item { SectionTitle("Activité récente", "Les dernières opérations SyncZen") }
            if (logs.isEmpty()) item { Text("Aucune synchronisation enregistrée pour le moment.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f)) }
            items(logs) { log -> LogRow(log) }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable private fun SectionTitle(title: String, subtitle: String) { Column { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f)) } }

@Composable private fun ThemeChoice(label: String, icon: String, mode: SyncThemeMode, selected: SyncThemeMode, onSelected: (SyncThemeMode) -> Unit, modifier: Modifier) {
    val active = mode == selected
    OutlinedButton(onClick = { onSelected(mode) }, modifier = modifier, contentPadding = PaddingValues(vertical = 11.dp), colors = ButtonDefaults.outlinedButtonColors(containerColor = if (active) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else MaterialTheme.colorScheme.surface)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(icon, style = MaterialTheme.typography.titleLarge, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface); Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
    }
}

@Composable private fun LogRow(log: SyncLogEntry) {
    val dateFormat = remember { SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE) }
    val (symbol, color) = when (log.status) { SyncStatus.SUCCESS -> "✓" to MaterialTheme.colorScheme.primary; SyncStatus.FAILURE -> "!" to MaterialTheme.colorScheme.error; SyncStatus.RUNNING -> "…" to MaterialTheme.colorScheme.secondary }
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = .14f)), contentAlignment = Alignment.Center) { Text(symbol, color = color, fontWeight = FontWeight.Bold) }
        Text("${dateFormat.format(Date(log.timestamp))}  ${log.message}", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
