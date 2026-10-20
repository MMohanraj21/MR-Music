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

    private val BRACKET_PATTERN = Regex("\\[.*?\\]|\\(.*?\\)")
    private val FEAT_PATTERN = Regex("\\b(feat|ft|featuring|remix|version|audio|video|official|lyric)\\b.*", RegexOption.IGNORE_CASE)
    private val NON_ALPHANUM_PATTERN = Regex("[^a-z0-9 ]")
    private val MULTI_SPACE_PATTERN = Regex("\\s+")

    private data class ProcessedSong(
        val song: Song,
        val cleanTitle: String,
        val cleanArtist: String,
        val durationSeconds: Long
    )

    fun scan(context: Context, songs: List<Song>): List<DuplicateGroup> {
        if (songs.size < 2) return emptyList()

        // 1. Pre-process and normalize all songs once in O(N)
        val processed = songs.mapNotNull { song ->
            val cleanTitle = normalize(song.title)
            if (cleanTitle.length < 2) null
            else ProcessedSong(
                song = song,
                cleanTitle = cleanTitle,
                cleanArtist = normalize(song.artist),
                durationSeconds = song.duration / 1000
            )
        }

        // 2. Bucket by cleanTitle in O(N)
        val titleBuckets = processed.groupBy { it.cleanTitle }

        val duplicateGroups = mutableListOf<DuplicateGroup>()

        // 3. Only inspect buckets with 2 or more songs with same title
        for ((_, bucketSongs) in titleBuckets) {
            if (bucketSongs.size < 2) continue

            // Cluster songs inside the bucket by duration (+/- 4 seconds) and matching artist
            val subClusters = mutableListOf<MutableList<ProcessedSong>>()

            for (item in bucketSongs) {
                val matchedCluster = subClusters.firstOrNull { cluster ->
                    val first = cluster.first()
                    val matchDuration = abs(item.song.duration - first.song.duration) <= 4000L
                    val matchArtist = isArtistSimilar(item.cleanArtist, first.cleanArtist)
                    matchDuration && matchArtist
                }

                if (matchedCluster != null) {
                    if (matchedCluster.none { it.song.id == item.song.id }) {
                        matchedCluster.add(item)
                    }
                } else {
                    subClusters.add(mutableListOf(item))
                }
            }

            for (cluster in subClusters) {
                if (cluster.size >= 2) {
                    val rawSongs = cluster.map { it.song }
                    val groupItems = rawSongs.map { song ->
                        val size = resolveFastFileSize(song)
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

                    // Rank: Lossless > Bitrate > File Size > Duration
                    val sortedItems = groupItems.sortedWith(
                        compareByDescending<DuplicateSongItem> { isLossless(it.song) }
                            .thenByDescending { it.song.bitrate ?: 0 }
                            .thenByDescending { it.fileSize }
                            .thenByDescending { it.song.duration }
                    )

                    val markedItems = sortedItems.mapIndexed { index, item ->
                        item.copy(isBestQuality = (index == 0))
                    }

                    val firstSong = markedItems.first().song
                    duplicateGroups.add(
                        DuplicateGroup(
                            groupKey = "${cluster.first().cleanTitle}_${firstSong.id}_${firstSong.duration / 1000}",
                            title = firstSong.title,
                            artist = firstSong.artist,
                            items = markedItems
                        )
                    )
                }
            }
        }

        return duplicateGroups.sortedByDescending { group ->
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
        val withoutBrackets = BRACKET_PATTERN.replace(text.lowercase(Locale.ROOT), "")
        val withoutFeat = FEAT_PATTERN.replace(withoutBrackets, "")
        val alphanumericOnly = NON_ALPHANUM_PATTERN.replace(withoutFeat, " ")
        return MULTI_SPACE_PATTERN.replace(alphanumericOnly, " ").trim()
    }

    private fun isArtistSimilar(a1: String, a2: String): Boolean {
        if (a1 == a2) return true
        if (a1.isEmpty() || a2.isEmpty()) return true
        return a1.startsWith(a2) || a2.startsWith(a1) || a1.contains(a2) || a2.contains(a1)
    }

    /**
     * Fast file size calculation without blocking Android ContentProvider IPC.
     */
    private fun resolveFastFileSize(song: Song): Long {
        if (!song.path.isNullOrBlank()) {
            try {
                val file = File(song.path)
                if (file.exists() && file.isFile) {
                    val len = file.length()
                    if (len > 0L) return len
                }
            } catch (_: Exception) {}
        }

        // Fast estimation if file stat is unavailable
        val bitrate = song.bitrate?.takeIf { it > 0 } ?: 160_000
        return (bitrate.toLong() * (song.duration / 1000L)) / 8L
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
