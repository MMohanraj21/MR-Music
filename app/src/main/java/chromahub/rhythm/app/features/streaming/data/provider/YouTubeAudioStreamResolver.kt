/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-FileCopyrightText: 2026 Mohanraj.MS (MR Music Studio)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.data.provider

import android.content.Context
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Resolves a Spotify track (Title + Artist) to a playable audio stream URL
 * using YouTube Music / Piped open streaming endpoints, with LRU caching.
 */
class YouTubeAudioStreamResolver(private val context: Context) {

    companion object {
        private const val TAG = "YTStreamResolver"

        // Public Piped API mirrors with high reliability
        private val PIPED_INSTANCES = listOf(
            "https://pipedapi.kavin.rocks",
            "https://api.piped.private.coffee",
            "https://pipedapi.adminforge.de",
            "https://pipedapi.leptons.xyz"
        )

        private const val YTM_API_URL = "https://music.youtube.com/youtubei/v1"
        private const val YTM_API_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"

        // Cache videoId matches: "title artist" -> "videoId"
        private val videoIdCache = LruCache<String, String>(250)

        // Cache resolved audio stream URLs: "videoId" -> "streamUrl"
        private val streamUrlCache = LruCache<String, String>(100)
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Resolves a track to a direct audio stream URL.
     */
    suspend fun resolveStreamUrl(title: String, artist: String, durationMs: Long = 0L): String? = withContext(Dispatchers.IO) {
        val queryKey = "${title.trim().lowercase()} ${artist.trim().lowercase()}"

        // 1. Check videoId cache
        var videoId = videoIdCache.get(queryKey)

        if (videoId.isNullOrBlank()) {
            videoId = searchVideoId(title, artist)
            if (!videoId.isNullOrBlank()) {
                videoIdCache.put(queryKey, videoId)
            }
        }

        if (videoId.isNullOrBlank()) {
            Log.w(TAG, "No matching YouTube videoId found for: $title by $artist")
            return@withContext null
        }

        // 2. Check stream URL cache
        val cachedStream = streamUrlCache.get(videoId)
        if (!cachedStream.isNullOrBlank()) {
            return@withContext cachedStream
        }

        // 3. Resolve direct audio stream
        val streamUrl = extractAudioStreamUrl(videoId)
        if (!streamUrl.isNullOrBlank()) {
            streamUrlCache.put(videoId, streamUrl)
            return@withContext streamUrl
        }

        Log.e(TAG, "Failed to resolve stream URL for videoId=$videoId ($title by $artist)")
        null
    }

    /**
     * Searches YouTube Music for the track and extracts the best videoId.
     */
    private fun searchVideoId(title: String, artist: String): String? {
        val cleanTitle = cleanSearchQuery(title)
        val cleanArtist = cleanSearchQuery(artist)
        val query = "$cleanTitle $cleanArtist"

        // Attempt 1: Piped search API
        for (instance in PIPED_INSTANCES) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val request = Request.Builder()
                    .url("$instance/search?q=$encodedQuery&filter=music_songs")
                    .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val items = json.optJSONArray("items")
                    if (items != null && items.length() > 0) {
                        val firstItem = items.getJSONObject(0)
                        val url = firstItem.optString("url", "")
                        val vId = if (url.contains("/watch?v=")) {
                            url.substringAfter("/watch?v=")
                        } else {
                            url.removePrefix("/")
                        }
                        if (vId.isNotBlank()) {
                            Log.d(TAG, "Found videoId=$vId via Piped for: $query")
                            return vId
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Piped instance $instance failed for search: ${e.message}")
            }
        }

        // Attempt 2: YouTubei InnerTube search endpoint
        try {
            val jsonBody = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20241211.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("query", query)
                // Filter for songs
                put("params", "Eg-KAQwIARAAGAAgACgAMABqChAEEAMQCRAFEAo%3D")
            }

            val request = Request.Builder()
                .url("$YTM_API_URL/search?key=$YTM_API_KEY")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:120.0)")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                val videoId = parseYouTubeiSearchForVideoId(body)
                if (!videoId.isNullOrBlank()) {
                    Log.d(TAG, "Found videoId=$videoId via YouTubei for: $query")
                    return videoId
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "YouTubei search failed: ${e.message}")
        }

        return null
    }

    /**
     * Extracts direct audio stream URL from videoId.
     */
    private fun extractAudioStreamUrl(videoId: String): String? {
        // Attempt 1: Piped Streams API
        for (instance in PIPED_INSTANCES) {
            try {
                val request = Request.Builder()
                    .url("$instance/streams/$videoId")
                    .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val audioStreams = json.optJSONArray("audioStreams")
                    if (audioStreams != null && audioStreams.length() > 0) {
                        // Pick the highest bitrate audio stream
                        var bestUrl: String? = null
                        var maxBitrate = 0
                        for (i in 0 until audioStreams.length()) {
                            val stream = audioStreams.getJSONObject(i)
                            val bitrate = stream.optInt("bitrate", 0)
                            val url = stream.optString("url")
                            if (url.isNotBlank() && bitrate >= maxBitrate) {
                                maxBitrate = bitrate
                                bestUrl = url
                            }
                        }
                        if (!bestUrl.isNullOrBlank()) {
                            Log.d(TAG, "Resolved audio stream from Piped: bitrate=$maxBitrate")
                            return bestUrl
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Piped stream extract failed on $instance: ${e.message}")
            }
        }

        // Attempt 2: YouTubei Android Client Player endpoint (unrestricted direct streams)
        try {
            val jsonBody = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID")
                        put("clientVersion", "19.29.37")
                        put("androidSdkVersion", 34)
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("videoId", videoId)
            }

            val request = Request.Builder()
                .url("$YTM_API_URL/player?key=$YTM_API_KEY")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "com.google.android.youtube/19.29.37 (Linux; U; Android 14)")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val streamingData = json.optJSONObject("streamingData")
                val adaptiveFormats = streamingData?.optJSONArray("adaptiveFormats")
                if (adaptiveFormats != null && adaptiveFormats.length() > 0) {
                    var bestAudioUrl: String? = null
                    var maxBitrate = 0
                    for (i in 0 until adaptiveFormats.length()) {
                        val format = adaptiveFormats.getJSONObject(i)
                        val mimeType = format.optString("mimeType", "")
                        if (mimeType.startsWith("audio/")) {
                            val url = format.optString("url")
                            val bitrate = format.optInt("bitrate", 0)
                            if (url.isNotBlank() && bitrate >= maxBitrate) {
                                maxBitrate = bitrate
                                bestAudioUrl = url
                            }
                        }
                    }
                    if (!bestAudioUrl.isNullOrBlank()) {
                        Log.d(TAG, "Resolved audio stream from YouTubei Android client: bitrate=$maxBitrate")
                        return bestAudioUrl
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "YouTubei player call failed: ${e.message}")
        }

        return null
    }

    /**
     * Helper to parse the nested videoId from YouTubei search response.
     */
    private fun parseYouTubeiSearchForVideoId(jsonString: String): String? {
        val marker = "\"videoId\":\""
        val startIndex = jsonString.indexOf(marker)
        if (startIndex != -1) {
            val idStart = startIndex + marker.length
            val idEnd = jsonString.indexOf("\"", idStart)
            if (idEnd != -1) {
                val candidateId = jsonString.substring(idStart, idEnd)
                if (candidateId.length == 11) { // Standard YouTube videoId length
                    return candidateId
                }
            }
        }
        return null
    }

    private fun cleanSearchQuery(text: String): String {
        return text
            .replace(Regex("""\([^)]*\)"""), "") // Remove parentheses
            .replace(Regex("""\[[^\]]*\]"""), "") // Remove brackets
            .replace(Regex("""[feat|ft]\.?\s+.*""", RegexOption.IGNORE_CASE), "") // Remove feat
            .trim()
    }
}
