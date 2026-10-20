package chromahub.rhythm.app.core.domain.scan

import android.content.Context
import chromahub.rhythm.app.shared.data.model.Song
import java.io.File
import java.util.Locale
import kotlin.math.abs

data class DuplicateSongItem(
    val song: Song,
    val fileSize: Long,
    val isBestQuality: Boolean,
    val audioFormatLabel: String,
    val folderPath: String
)

data class DuplicateGroup(
    val groupKey: String,
    val title: String,
    val artist: String,
    val items: List<DuplicateSongItem>
)

object DuplicateSongScanner {

    fun scan(context: Context, songs: List<Song>): List<DuplicateGroup> {
        if (songs.size < 2) return emptyList()

        val candidateGroups = mutableListOf<MutableList<Song>>()
        val sortedSongs = songs.sortedWith(compareBy({ normalize(it.title) }, { normalize(it.artist) }))

        for (song in sortedSongs) {
            val normTitle = normalize(song.title)
            val normArtist = normalize(song.artist)
            if (normTitle.isEmpty()) continue

            // Look for existing group matching title, artist, and duration tolerance (+/- 3 seconds)
            val matchedGroup = candidateGroups.firstOrNull { group ->
                val first = group.first()
                val matchTitle = isTitleSimilar(normTitle, normalize(first.title))
                val matchArtist = isArtistSimilar(normArtist, normalize(first.artist))
                val matchDuration = abs(song.duration - first.duration) <= 3500L
                matchTitle && matchArtist && matchDuration
            }

            if (matchedGroup != null) {
                if (matchedGroup.none { it.id == song.id }) {
                    matchedGroup.add(song)
                }
            } else {
                candidateGroups.add(mutableListOf(song))
            }
        }

        val duplicateGroups = candidateGroups.filter { it.size >= 2 }

        return duplicateGroups.map { songList ->
            val items = songList.map { song ->
                val size = resolveFileSize(context, song)
                val formatLabel = resolveFormatLabel(song, size)
                val folder = resolveFolder(song)
                DuplicateSongItem(
                    song = song,
                    fileSize = size,
                    isBestQuality = false,
                    audioFormatLabel = formatLabel,
                    folderPath = folder
                )
            }

            // Rank items to find the best quality candidate
            val sortedItems = items.sortedWith(
                compareByDescending<DuplicateSongItem> { isLossless(it.song) }
                    .thenByDescending { it.song.bitrate ?: 0 }
                    .thenByDescending { it.fileSize }
                    .thenByDescending { it.song.duration }
            )

            val markedItems = sortedItems.mapIndexed { index, item ->
                item.copy(isBestQuality = (index == 0))
            }

            DuplicateGroup(
                groupKey = "${normalize(markedItems.first().song.title)}_${normalize(markedItems.first().song.artist)}_${markedItems.first().song.duration / 1000}",
                title = markedItems.first().song.title,
                artist = markedItems.first().song.artist,
                items = markedItems
            )
        }.sortedByDescending { group ->
            // Sort groups by potential wasted storage (sum of non-best quality copies)
            group.items.drop(1).sumOf { it.fileSize }
        }
    }

    private fun isLossless(song: Song): Boolean {
        val codec = song.codec?.lowercase(Locale.ROOT) ?: ""
        val path = song.path?.lowercase(Locale.ROOT) ?: ""
        return codec.contains("flac") || codec.contains("wav") || codec.contains("alac") ||
                path.endsWith(".flac") || path.endsWith(".wav")
    }

    private fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text.lowercase(Locale.ROOT)
            .replace(Regex("\\[.*?\\]|\\(.*?\\)"), "") // remove bracketed text like (official video)
            .replace(Regex("\\b(feat|ft|featuring|remix|version|audio|video)\\b.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isTitleSimilar(t1: String, t2: String): Boolean {
        if (t1 == t2) return true
        if (t1.isEmpty() || t2.isEmpty()) return false
        return t1.startsWith(t2) || t2.startsWith(t1) || t1.contains(t2) || t2.contains(t1)
    }

    private fun isArtistSimilar(a1: String, a2: String): Boolean {
        if (a1 == a2) return true
        if (a1.isEmpty() || a2.isEmpty()) return true // Unknown artist match
        return a1.startsWith(a2) || a2.startsWith(a1) || a1.contains(a2) || a2.contains(a1)
    }

    private fun resolveFileSize(context: Context, song: Song): Long {
        if (!song.path.isNullOrBlank()) {
            val file = File(song.path)
            if (file.exists() && file.isFile) {
                return file.length()
            }
        }
        return try {
            context.contentResolver.openFileDescriptor(song.uri, "r")?.use {
                it.statSize
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun resolveFormatLabel(song: Song, size: Long): String {
        val bitrateStr = song.bitrate?.takeIf { it > 0 }?.let { "${it / 1000} kbps" }
        val codecStr = song.codec?.uppercase(Locale.ROOT)
            ?: song.path?.substringAfterLast('.', "")?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
            ?: "AUDIO"
        val sizeStr = formatBytes(size)

        return buildList {
            add(codecStr)
            if (bitrateStr != null) add(bitrateStr)
            if (size > 0) add(sizeStr)
        }.joinToString(" • ")
    }

    private fun resolveFolder(song: Song): String {
        return song.path?.let { path ->
            val file = File(path)
            file.parentFile?.name ?: file.parent ?: ""
        } ?: "Internal Storage"
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
            else -> "$bytes B"
        }
    }
}
