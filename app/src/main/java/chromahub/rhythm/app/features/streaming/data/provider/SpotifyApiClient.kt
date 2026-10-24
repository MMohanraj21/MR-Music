/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-FileCopyrightText: 2026 Mohanraj.MS (MR Music Studio)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.data.provider

import android.content.Context
import android.util.Log
import chromahub.rhythm.app.core.domain.model.SourceType
import chromahub.rhythm.app.features.streaming.domain.model.PlaylistOwner
import chromahub.rhythm.app.features.streaming.domain.model.StreamingPlaylist
import chromahub.rhythm.app.features.streaming.domain.model.StreamingSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SpotifyApiClient(private val context: Context) {

    companion object {
        private const val TAG = "SpotifyApiClient"
        private const val BASE_URL = "https://api.spotify.com/v1"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private suspend fun getAuthHeader(): String? {
        val token = SpotifyAuthManager.getValidAccessToken(context) ?: return null
        return "Bearer $token"
    }

    /**
     * Fetches the user's saved (liked) songs from Spotify.
     */
    suspend fun getLikedSongs(limit: Int = 50, offset: Int = 0): List<StreamingSong> = withContext(Dispatchers.IO) {
        val auth = getAuthHeader() ?: return@withContext emptyList()
        val url = "$BASE_URL/me/tracks?limit=$limit&offset=$offset"

        try {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", auth)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to fetch liked songs: HTTP ${response.code}")
                return@withContext emptyList()
            }

            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            val items = json.optJSONArray("items") ?: return@withContext emptyList()
            val songs = mutableListOf<StreamingSong>()

            for (i in 0 until items.length()) {
                val itemObj = items.getJSONObject(i)
                val trackObj = itemObj.optJSONObject("track") ?: continue
                parseSpotifyTrack(trackObj, isFavorite = true)?.let { songs.add(it) }
            }

            songs
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Spotify liked songs: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Fetches all user playlists from Spotify.
     */
    suspend fun getUserPlaylists(limit: Int = 50, offset: Int = 0): List<StreamingPlaylist> = withContext(Dispatchers.IO) {
        val auth = getAuthHeader() ?: return@withContext emptyList()
        val url = "$BASE_URL/me/playlists?limit=$limit&offset=$offset"

        try {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", auth)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to fetch user playlists: HTTP ${response.code}")
                return@withContext emptyList()
            }

            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            val items = json.optJSONArray("items") ?: return@withContext emptyList()
            val playlists = mutableListOf<StreamingPlaylist>()

            for (i in 0 until items.length()) {
                val plObj = items.getJSONObject(i)
                val id = plObj.optString("id")
                if (id.isBlank()) continue

                val name = plObj.optString("name", "Untitled Playlist")
                val description = plObj.optString("description", "")
                val tracksInfo = plObj.optJSONObject("tracks")
                val trackCount = tracksInfo?.optInt("total", 0) ?: 0

                val images = plObj.optJSONArray("images")
                val artworkUri = if (images != null && images.length() > 0) {
                    images.getJSONObject(0).optString("url", null)
                } else null

                val ownerObj = plObj.optJSONObject("owner")
                val owner = if (ownerObj != null) {
                    PlaylistOwner(
                        id = ownerObj.optString("id", ""),
                        displayName = ownerObj.optString("display_name", "Spotify User")
                    )
                } else null

                playlists.add(
                    StreamingPlaylist(
                        id = "spotify_pl_$id",
                        name = name,
                        description = description.ifBlank { null },
                        artworkUri = artworkUri,
                        songCount = trackCount,
                        isEditable = plObj.optBoolean("collaborative", false),
                        sourceType = SourceType.SPOTIFY,
                        externalId = id,
                        owner = owner,
                        isPublic = plObj.optBoolean("public", true),
                        isCollaborative = plObj.optBoolean("collaborative", false)
                    )
                )
            }

            playlists
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching Spotify playlists: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Fetches tracks inside a specific Spotify playlist.
     */
    suspend fun getPlaylistTracks(playlistExternalId: String, limit: Int = 100, offset: Int = 0): List<StreamingSong> = withContext(Dispatchers.IO) {
        val auth = getAuthHeader() ?: return@withContext emptyList()
        val url = "$BASE_URL/playlists/$playlistExternalId/tracks?limit=$limit&offset=$offset"

        try {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", auth)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to fetch playlist tracks for $playlistExternalId: HTTP ${response.code}")
                return@withContext emptyList()
            }

            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            val items = json.optJSONArray("items") ?: return@withContext emptyList()
            val songs = mutableListOf<StreamingSong>()

            for (i in 0 until items.length()) {
                val itemObj = items.getJSONObject(i)
                val trackObj = itemObj.optJSONObject("track") ?: continue
                parseSpotifyTrack(trackObj)?.let { songs.add(it) }
            }

            songs
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching tracks for playlist $playlistExternalId: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Parses a Spotify Track JSON object into MR Music's StreamingSong model.
     */
    private fun parseSpotifyTrack(trackObj: JSONObject, isFavorite: Boolean = false): StreamingSong? {
        val id = trackObj.optString("id")
        if (id.isBlank()) return null

        val name = trackObj.optString("name", "Unknown Title")
        val durationMs = trackObj.optLong("duration_ms", 0L)

        // Parse Artists
        val artistsArray = trackObj.optJSONArray("artists")
        val artistNames = mutableListOf<String>()
        var primaryArtist = "Unknown Artist"
        if (artistsArray != null && artistsArray.length() > 0) {
            for (j in 0 until artistsArray.length()) {
                val aName = artistsArray.getJSONObject(j).optString("name")
                if (aName.isNotBlank()) artistNames.add(aName)
            }
            if (artistNames.isNotEmpty()) {
                primaryArtist = artistNames.joinToString(", ")
            }
        }

        // Parse Album & Artwork
        val albumObj = trackObj.optJSONObject("album")
        val albumName = albumObj?.optString("name", "Unknown Album") ?: "Unknown Album"
        val albumImages = albumObj?.optJSONArray("images")
        val artworkUri = if (albumImages != null && albumImages.length() > 0) {
            albumImages.getJSONObject(0).optString("url", null)
        } else null

        val isrc = trackObj.optJSONObject("external_ids")?.optString("isrc", null)
        val previewUrl = trackObj.optString("preview_url", null)

        return StreamingSong(
            id = "spotify_$id",
            title = name,
            artist = primaryArtist,
            album = albumName,
            duration = durationMs,
            artworkUri = artworkUri,
            sourceType = SourceType.SPOTIFY,
            streamingUrl = null, // Dynamically resolved on playback by YouTubeAudioStreamResolver
            previewUrl = previewUrl,
            isExplicit = trackObj.optBoolean("explicit", false),
            popularity = trackObj.optInt("popularity", 0),
            externalId = id,
            isrc = isrc,
            isFavorite = isFavorite,
            trackNumber = trackObj.optInt("track_number", 1)
        )
    }
}
