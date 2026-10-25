/*
 * SPDX-FileCopyrightText: 2026 Mohanraj.MS (MR Music Studio)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.jam.presentation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chromahub.rhythm.app.features.jam.model.JamReaction
import chromahub.rhythm.app.features.jam.service.ListenTogetherManager
import chromahub.rhythm.app.features.local.presentation.viewmodel.MusicViewModel
import kotlinx.coroutines.launch

private val REACTION_EMOJIS = listOf("❤️", "🔥", "👏", "🎉", "🚀", "🎵")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenTogetherBottomSheet(
    onDismiss: () -> Unit,
    sheetState: SheetState,
    musicViewModel: MusicViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val roomCode by ListenTogetherManager.roomCode.collectAsState()
    val isHost by ListenTogetherManager.isHost.collectAsState()
    val isConnected by ListenTogetherManager.isConnected.collectAsState()
    val users by ListenTogetherManager.users.collectAsState()
    val currentJamSong by ListenTogetherManager.currentSong.collectAsState()
    val isPlaying by ListenTogetherManager.isPlaying.collectAsState()
    val reactions by ListenTogetherManager.reactions.collectAsState()
    val syncStatus by ListenTogetherManager.syncStatus.collectAsState()

    var userNameInput by remember { mutableStateOf("Music Fan") }
    var roomCodeInput by remember { mutableStateOf("") }
    var copiedCode by remember { mutableStateOf(false) }

    // Wire ListenTogetherManager to MusicViewModel playback
    LaunchedEffect(Unit) {
        ListenTogetherManager.getCurrentPositionMs = {
            musicViewModel.currentPosition.value
        }
        ListenTogetherManager.onRemoteSeek = { posMs ->
            musicViewModel.seekTo(posMs)
        }
        ListenTogetherManager.onRemotePlay = {
            musicViewModel.resumeMusic()
        }
        ListenTogetherManager.onRemotePause = {
            musicViewModel.pauseMusic()
        }
        ListenTogetherManager.onRemoteTrackChange = { jamSong ->
            val matching = musicViewModel.songs.value.find {
                it.title.equals(jamSong.title, ignoreCase = true) ||
                it.artist.equals(jamSong.artist, ignoreCase = true)
            }
            if (matching != null) {
                musicViewModel.playSong(matching)
            }
        }
    }

    // Keep Host playback continuously broadcasted to listeners
    val hostCurrentSong by musicViewModel.currentSong.collectAsState()
    val hostIsPlaying by musicViewModel.isPlaying.collectAsState()
    val hostPosition by musicViewModel.currentPosition.collectAsState()

    LaunchedEffect(hostCurrentSong, hostIsPlaying) {
        if (isHost && roomCode != null) {
            ListenTogetherManager.broadcastPlaybackState(hostIsPlaying, hostPosition, hostCurrentSong)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            // Live Floating Emoji Reactions Canvas
            FloatingReactionsCanvas(reactions = reactions)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFFFF416C), Color(0xFFFF4B2B))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("📻", fontSize = 18.sp)
                        }
                        Column {
                            Text(
                                text = "Listen Together",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "MR Music Real-Time Jam Session",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Content Views
                if (roomCode == null) {
                    /* ================= VIEW A: Create or Join ================= */
                    OutlinedTextField(
                        value = userNameInput,
                        onValueChange = { userNameInput = it },
                        label = { Text("Your Display Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Host Button
                    Button(
                        onClick = {
                            ListenTogetherManager.createRoom(
                                context = context,
                                hostName = userNameInput,
                                initialSong = musicViewModel.currentSong.value
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF4B2B)
                        )
                    ) {
                        Text(
                            text = "✨ Start a New Jam Session (Host)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                        Text(
                            text = " OR JOIN WITH CODE ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        Box(modifier = Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Join with 6-char code
                    OutlinedTextField(
                        value = roomCodeInput,
                        onValueChange = { if (it.length <= 6) roomCodeInput = it.uppercase() },
                        label = { Text("6-CHARACTER ROOM CODE") },
                        placeholder = { Text("E.G. MR08IT") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Join
                        ),
                        keyboardActions = KeyboardActions(
                            onJoin = {
                                if (roomCodeInput.isNotBlank()) {
                                    ListenTogetherManager.joinRoom(roomCodeInput, userNameInput)
                                }
                            }
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            if (roomCodeInput.isNotBlank()) {
                                ListenTogetherManager.joinRoom(roomCodeInput, userNameInput)
                            }
                        },
                        enabled = roomCodeInput.trim().length >= 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.filledTonalButtonColors()
                    ) {
                        Text("🎧 Join Room", fontWeight = FontWeight.SemiBold)
                    }

                    if (syncStatus != "Idle") {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = syncStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    /* ================= VIEW B: In Session ================= */
                    // Room Code Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (isHost) "👑 YOU ARE THE HOST" else "🎧 LISTENER MODE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = roomCode ?: "",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 4.sp
                                )

                                Spacer(modifier = Modifier.width(14.dp))

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (copiedCode) Color(0xFF34C759) else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Room Code", roomCode)
                                        clipboard.setPrimaryClip(clip)
                                        copiedCode = true
                                        Toast.makeText(context, "Room Code Copied! Share with friends", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Text(
                                        text = if (copiedCode) "✓ Copied" else "📋 Copy",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Active Listeners Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ACTIVE PARTICIPANTS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF34C759).copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF34C759))
                            )
                            Text(
                                text = "${users.size.coerceAtLeast(1)} Online",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF34C759)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Users List
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(users) { user ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (user.isHost) Color(0xFFFFD700).copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
                                border = if (user.isHost) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFD700).copy(alpha = 0.5f)) else null
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(if (user.isHost) "👑" else "👤")
                                    Text(
                                        text = user.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Live Emoji Reactions Bar
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = "SEND REACTION",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            REACTION_EMOJIS.forEach { emoji ->
                                Text(
                                    text = emoji,
                                    fontSize = 24.sp,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable {
                                            ListenTogetherManager.sendReaction(emoji, userNameInput)
                                        }
                                        .padding(6.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Leave Session Button
                    Button(
                        onClick = {
                            ListenTogetherManager.leaveRoom()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Text("🚪 Leave Jam Session", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Animated Floating Reactions Overlay Component.
 */
@Composable
private fun FloatingReactionsCanvas(reactions: List<JamReaction>) {
    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        reactions.takeLast(6).forEachIndexed { index, reaction ->
            key(reaction.id) {
                FloatingEmojiItem(
                    emoji = reaction.emoji,
                    userName = reaction.userName,
                    slotIndex = index
                )
            }
        }
    }
}

@Composable
private fun FloatingEmojiItem(
    emoji: String,
    userName: String,
    slotIndex: Int
) {
    val offsetY = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        launch {
            offsetY.animateTo(-220f, tween(durationMillis = 3000))
        }
        launch {
            alpha.animateTo(0f, tween(durationMillis = 3000))
        }
    }

    val horizontalOffset = remember(slotIndex) {
        (20 + ((slotIndex * 37) % 60)).dp
    }

    Box(
        modifier = Modifier
            .offset(x = horizontalOffset, y = 200.dp + offsetY.value.dp)
            .alpha(alpha.value)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF222228).copy(alpha = 0.85f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(emoji, fontSize = 20.sp)
            Text(userName, fontSize = 11.sp, color = Color.LightGray, fontWeight = FontWeight.SemiBold)
        }
    }
}
