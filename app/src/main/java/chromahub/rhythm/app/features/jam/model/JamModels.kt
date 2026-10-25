/*
 * SPDX-FileCopyrightText: 2026 Mohanraj.MS (MR Music Studio)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.jam.model

/**
 * Metadata for a song synchronized across the Jam Session.
 */
data class JamSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0L,
    val artworkUri: String? = null
)

/**
 * A participant in the Jam Session.
 */
data class JamUser(
    val id: String,
    val name: String,
    val isHost: Boolean,
    val joinedAtMs: Long = System.currentTimeMillis()
)

/**
 * Playback synchronization payload.
 */
data class JamSyncState(
    val roomId: String,
    val hostId: String,
    val song: JamSong?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val serverTimestampMs: Long
)

/**
 * Real-time floating emoji reaction.
 */
data class JamReaction(
    val id: String,
    val emoji: String,
    val userName: String,
    val timestampMs: Long = System.currentTimeMillis()
)
