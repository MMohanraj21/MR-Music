/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-FileCopyrightText: 2026 Mohanraj.MS (MR Music Studio)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.data.provider

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import chromahub.rhythm.app.features.streaming.domain.model.StreamingServiceId
import chromahub.rhythm.app.shared.data.model.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

object SpotifyAuthManager {
    private const val TAG = "SpotifyAuthManager"
    const val REDIRECT_URI = "mrmusic://spotify-callback"
    private const val AUTH_URL = "https://accounts.spotify.com/authorize"
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
    private const val ME_URL = "https://api.spotify.com/v1/me"

    private const val SCOPES = "user-read-private user-read-email user-library-read playlist-read-private playlist-read-collaborative user-top-read"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Generates a cryptographically random, URL-safe code verifier for PKCE.
     */
    private fun generateCodeVerifier(): String {
        val secureRandom = SecureRandom()
        val code = ByteArray(64)
        secureRandom.nextBytes(code)
        return Base64.encodeToString(code, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /**
     * Generates a SHA-256 code challenge from the code verifier for PKCE.
     */
    private fun generateCodeChallenge(verifier: String): String {
        val bytes = verifier.toByteArray(Charsets.US_ASCII)
        val messageDigest = MessageDigest.getInstance("SHA-256")
        messageDigest.update(bytes, 0, bytes.size)
        val digest = messageDigest.digest()
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /**
     * Launches the Spotify Authorization page using Chrome Custom Tabs or system browser.
     */
    fun startLogin(context: Context) {
        val appSettings = AppSettings.getInstance(context)
        val clientId = appSettings.spotifyClientId.value.ifBlank { AppSettings.DEFAULT_SPOTIFY_CLIENT_ID }
        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)

        appSettings.setSpotifyCodeVerifier(verifier)

        val encodedScopes = URLEncoder.encode(SCOPES, "UTF-8")
        val encodedRedirect = URLEncoder.encode(REDIRECT_URI, "UTF-8")
        val authUri = Uri.parse(
            "$AUTH_URL?client_id=$clientId&response_type=code&redirect_uri=$encodedRedirect" +
                    "&code_challenge_method=S256&code_challenge=$challenge&scope=$encodedScopes"
        )

        Log.d(TAG, "Starting Spotify login with clientId=$clientId, redirect=$REDIRECT_URI")

        try {
            val customTabsIntent = CustomTabsIntent.Builder().build()
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabsIntent.launchUrl(context, authUri)
        } catch (e: Exception) {
            Log.w(TAG, "CustomTabs failed, falling back to standard ACTION_VIEW: ${e.message}")
            val browserIntent = Intent(Intent.ACTION_VIEW, authUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
        }
    }

    /**
     * Handles the callback intent redirect from Spotify (`mrmusic://spotify-callback?code=...`).
     * Exchanges authorization code for access and refresh tokens.
     */
    suspend fun handleAuthCallback(context: Context, callbackUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        val error = callbackUri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            Log.e(TAG, "Spotify auth callback returned error: $error")
            return@withContext Result.failure(Exception("Spotify authorization rejected: $error"))
        }

        val code = callbackUri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            Log.e(TAG, "Spotify auth callback missing code parameter")
            return@withContext Result.failure(Exception("Missing authorization code"))
        }

        val appSettings = AppSettings.getInstance(context)
        val clientId = appSettings.spotifyClientId.value.ifBlank { AppSettings.DEFAULT_SPOTIFY_CLIENT_ID }
        val verifier = appSettings.spotifyCodeVerifier.value

        try {
            val requestBody = FormBody.Builder()
                .add("client_id", clientId)
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("code_verifier", verifier)
                .build()

            val request = Request.Builder()
                .url(TOKEN_URL)
                .post(requestBody)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                Log.e(TAG, "Token exchange failed: HTTP ${response.code} body: $responseBody")
                return@withContext Result.failure(Exception("Token exchange error: HTTP ${response.code}"))
            }

            val json = JSONObject(responseBody)
            val accessToken = json.getString("access_token")
            val refreshToken = json.optString("refresh_token", null)
            val expiresIn = json.optLong("expires_in", 3600L)

            // Save tokens
            appSettings.setSpotifyAuthSession(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresInSeconds = expiresIn
            )

            // Fetch current user profile
            fetchAndSaveUserProfile(appSettings, accessToken)

            // Set Spotify as current streaming service
            appSettings.setStreamingService(StreamingServiceId.SPOTIFY)
            appSettings.setSpotifyApiEnabled(true)
            appSettings.setIntegrationsEnabled(true)

            Log.i(TAG, "Successfully authenticated with Spotify! User: ${appSettings.spotifyUserDisplayName.value}")
            Result.success(appSettings.spotifyUserDisplayName.value)
        } catch (e: Exception) {
            Log.e(TAG, "Error exchanging Spotify auth code: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Fetches user profile from /v1/me and updates AppSettings.
     */
    private fun fetchAndSaveUserProfile(appSettings: AppSettings, accessToken: String) {
        try {
            val meRequest = Request.Builder()
                .url(ME_URL)
                .header("Authorization", "Bearer $accessToken")
                .build()

            val meResponse = httpClient.newCall(meRequest).execute()
            if (meResponse.isSuccessful) {
                val meJson = JSONObject(meResponse.body?.string().orEmpty())
                val displayName = meJson.optString("display_name", "")
                val id = meJson.optString("id", "")
                val imagesArray = meJson.optJSONArray("images")
                val avatarUrl = if (imagesArray != null && imagesArray.length() > 0) {
                    imagesArray.getJSONObject(0).optString("url", "")
                } else ""

                appSettings.setSpotifyAuthSession(
                    accessToken = accessToken,
                    refreshToken = null,
                    expiresInSeconds = 3600L,
                    displayName = displayName.ifBlank { id },
                    userId = id,
                    avatarUrl = avatarUrl
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch user profile: ${e.message}")
        }
    }

    /**
     * Returns a valid access token. Automatically refreshes using refresh_token if expired.
     */
    suspend fun getValidAccessToken(context: Context): String? = withContext(Dispatchers.IO) {
        val appSettings = AppSettings.getInstance(context)
        val token = appSettings.spotifyAccessToken.value
        val expiresAt = appSettings.spotifyTokenExpiresAt.value
        val refreshToken = appSettings.spotifyRefreshToken.value
        val clientId = appSettings.spotifyClientId.value.ifBlank { AppSettings.DEFAULT_SPOTIFY_CLIENT_ID }

        if (token.isBlank()) return@withContext null

        // If valid for more than 60 seconds, reuse it
        if (System.currentTimeMillis() < (expiresAt - 60_000L)) {
            return@withContext token
        }

        // Token expired or about to expire, refresh it
        if (refreshToken.isBlank()) {
            Log.w(TAG, "Access token expired and no refresh token available")
            return@withContext null
        }

        try {
            Log.d(TAG, "Refreshing Spotify access token...")
            val requestBody = FormBody.Builder()
                .add("client_id", clientId)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build()

            val request = Request.Builder()
                .url(TOKEN_URL)
                .post(requestBody)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                Log.e(TAG, "Token refresh failed: HTTP ${response.code}")
                return@withContext null
            }

            val json = JSONObject(responseBody)
            val newAccessToken = json.getString("access_token")
            val newRefreshToken = json.optString("refresh_token", refreshToken)
            val expiresIn = json.optLong("expires_in", 3600L)

            appSettings.setSpotifyAuthSession(
                accessToken = newAccessToken,
                refreshToken = newRefreshToken,
                expiresInSeconds = expiresIn
            )

            Log.i(TAG, "Spotify access token successfully refreshed")
            newAccessToken
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing Spotify token: ${e.message}", e)
            null
        }
    }

    /**
     * Checks if Spotify is currently authenticated.
     */
    fun isAuthenticated(context: Context): Boolean {
        val appSettings = AppSettings.getInstance(context)
        return appSettings.spotifyAccessToken.value.isNotBlank()
    }

    /**
     * Logs out the user from Spotify by clearing tokens.
     */
    fun logout(context: Context) {
        val appSettings = AppSettings.getInstance(context)
        appSettings.clearSpotifyAuth()
        Log.i(TAG, "Spotify session cleared")
    }
}
