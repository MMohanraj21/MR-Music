package chromahub.rhythm.app.shared.presentation.screens.settings

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import chromahub.rhythm.app.core.domain.scan.DuplicateGroup
import chromahub.rhythm.app.core.domain.scan.DuplicateSongItem
import chromahub.rhythm.app.core.domain.scan.DuplicateSongScanner
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import chromahub.rhythm.app.shared.data.model.AppSettings
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.shared.presentation.components.common.CollapsibleHeaderScreen
import chromahub.rhythm.app.shared.presentation.components.icons.Icon
import chromahub.rhythm.app.shared.presentation.components.icons.MaterialSymbolIcon
import chromahub.rhythm.app.shared.presentation.components.icons.RhythmIcons
import chromahub.rhythm.app.ui.LocalMiniPlayerPadding
import chromahub.rhythm.app.util.HapticType
import chromahub.rhythm.app.util.HapticUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicateCleanerScreen(
    onBackClick: () -> Unit,
    musicViewModel: MusicViewModel = viewModel()
) {
    val context = LocalContext.current
    val appSettings = remember { AppSettings.getInstance(context) }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val songs by musicViewModel.songs.collectAsState()
    val currentSong by musicViewModel.currentSong.collectAsState()
    val isPlaying by musicViewModel.isPlaying.collectAsState()

    var isScanning by remember { mutableStateOf(true) }
    var duplicateGroups by remember { mutableStateOf<List<DuplicateGroup>>(emptyList()) }
    val selectedSongIds = remember { mutableStateMapOf<String, Boolean>() }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    fun refreshScan() {
        scope.launch {
            isScanning = true
            try {
                val currentSongs = musicViewModel.songs.value
                val results = withContext(Dispatchers.Default) {
                    DuplicateSongScanner.scan(context, currentSongs)
                }
                duplicateGroups = results
                selectedSongIds.clear()
                results.forEach { group ->
                    group.items.forEach { item ->
                        if (!item.isBestQuality) {
                            selectedSongIds[item.song.id] = true
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("DuplicateCleaner", "Error during duplicate scan", e)
            } finally {
                isScanning = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshScan()
    }

    val selectedCount = selectedSongIds.count { it.value }
    val selectedBytes = remember(duplicateGroups, selectedSongIds.toMap()) {
        duplicateGroups.flatMap { it.items }
            .filter { selectedSongIds[it.song.id] == true }
            .sumOf { it.fileSize }
    }

    CollapsibleHeaderScreen(
        title = "Duplicate Cleaner",
        showBackButton = true,
        onBackClick = onBackClick
    ) { modifier ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (isScanning) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Scanning library for duplicate songs...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (duplicateGroups.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.size(72.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = RhythmIcons.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No Duplicates Found!",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Your music library is clean and well-organized.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    FilledTonalButton(
                        onClick = { refreshScan() }
                    ) {
                        Icon(imageVector = RhythmIcons.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan Again")
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 120.dp + LocalMiniPlayerPadding.current.calculateBottomPadding()
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Header Summary Banner
                    item {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${duplicateGroups.size} Duplicate Groups Found",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Selected: $selectedCount items (${DuplicateSongScanner.formatBytes(selectedBytes)})",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    IconButton(onClick = { refreshScan() }) {
                                        Icon(imageVector = RhythmIcons.Refresh, contentDescription = "Rescan")
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            duplicateGroups.forEach { group ->
                                                group.items.forEach { item ->
                                                    if (!item.isBestQuality) {
                                                        selectedSongIds[item.song.id] = true
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Text("Select Duplicates", fontSize = 12.sp)
                                    }

                                    OutlinedButton(
                                        onClick = { selectedSongIds.clear() },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Text("Clear Selection", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    // Duplicate Groups
                    items(duplicateGroups, key = { it.groupKey }) { group ->
                        DuplicateGroupCard(
                            group = group,
                            selectedSongIds = selectedSongIds,
                            currentPlayingSong = currentSong,
                            isPlaying = isPlaying,
                            onToggleSelection = { songId ->
                                HapticUtils.performHapticFeedback(context, haptics, HapticType.LIGHT)
                                val current = selectedSongIds[songId] ?: false
                                selectedSongIds[songId] = !current
                            },
                            onPlaySong = { song ->
                                HapticUtils.performHapticFeedback(context, haptics, HapticType.MEDIUM)
                                musicViewModel.playSong(song)
                            }
                        )
                    }
                }

                // Bottom Action Bar
                if (selectedCount > 0) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(16.dp)
                            .padding(bottom = LocalMiniPlayerPadding.current.calculateBottomPadding()),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val idsToHide = selectedSongIds.filter { it.value }.keys.toList()
                                    idsToHide.forEach { appSettings.addToBlacklist(it) }
                                    musicViewModel.refreshLibrary(showMediaScanLoader = false)
                                    Toast.makeText(
                                        context,
                                        "${idsToHide.size} duplicate songs hidden from library",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    refreshScan()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Hide from Library", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }

                            Button(
                                onClick = { showDeleteConfirmDialog = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(imageVector = RhythmIcons.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Delete ($selectedCount)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirmDialog) {
        val selectedSongs = remember(duplicateGroups, selectedSongIds.toMap()) {
            duplicateGroups.flatMap { it.items }
                .map { it.song }
                .filter { selectedSongIds[it.id] == true }
        }

        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Duplicate Files?") },
            text = {
                Text(
                    "Are you sure you want to permanently delete ${selectedSongs.size} audio files from your device storage? This cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    onClick = {
                        showDeleteConfirmDialog = false
                        selectedSongs.forEach { song ->
                            musicViewModel.deleteSong(song)
                        }
                        Toast.makeText(
                            context,
                            "Deleting ${selectedSongs.size} duplicate files...",
                            Toast.LENGTH_SHORT
                        ).show()
                        refreshScan()
                    }
                ) {
                    Text("Delete Permanently")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    selectedSongIds: Map<String, Boolean>,
    currentPlayingSong: Song?,
    isPlaying: Boolean,
    onToggleSelection: (String) -> Unit,
    onPlaySong: (Song) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = group.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = group.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(10.dp))

            group.items.forEach { item ->
                DuplicateSongRow(
                    item = item,
                    isSelected = selectedSongIds[item.song.id] == true,
                    isCurrentlyPlaying = isPlaying && currentPlayingSong?.id == item.song.id,
                    onToggleSelection = { onToggleSelection(item.song.id) },
                    onPlayClick = { onPlaySong(item.song) }
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun DuplicateSongRow(
    item: DuplicateSongItem,
    isSelected: Boolean,
    isCurrentlyPlaying: Boolean,
    onToggleSelection: () -> Unit,
    onPlayClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        } else if (item.isBestQuality) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onToggleSelection() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelection() },
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.error
                )
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.isBestQuality) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Text(
                                text = "KEEP BEST",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Text(
                        text = item.audioFormatLabel,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (item.isBestQuality) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "📁 ${item.folderPath}",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onPlayClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (isCurrentlyPlaying) RhythmIcons.Pause else RhythmIcons.Play,
                    contentDescription = "Preview Audio",
                    tint = if (isCurrentlyPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
