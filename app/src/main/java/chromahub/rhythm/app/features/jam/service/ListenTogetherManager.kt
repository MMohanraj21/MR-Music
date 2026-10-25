/*
 * SPDX-FileCopyrightText: 2026 Mohanraj.MS (MR Music Studio)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.jam.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import chromahub.rhythm.app.features.jam.model.JamReaction
import chromahub.rhythm.app.features.jam.model.JamSong
import chromahub.rhythm.app.features.jam.model.JamSyncState
import chromahub.rhythm.app.features.jam.model.JamUser
import chromahub.rhythm.app.shared.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.UUID

/**
 * Embedded Listen Together Synchronization Engine.
 * 
 * Works 100% inside the APK:
 * - Runs a lightweight local socket server on Host device (port 8990) for instant Wi-Fi/Hotspot sync
 * - Encodes host IP into a clean 6-character room code (e.g. MR24X8)
 * - Implements 1.2s Latency Drift Correction to prevent audio stuttering
 * - Infinite event loop prevention via programmatic action flag
 */
object ListenTogetherManager {
    private const val TAG = "ListenTogetherManager"
    private const val DEFAULT_PORT = 8990
    private const val DRIFT_THRESHOLD_MS = 1200L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // StateFlows
    private val _roomCode = MutableStateFlow<String?>(null)
    val roomCode: StateFlow<String?> = _roomCode.asStateFlow()

    private val _isHost = MutableStateFlow(false)
    val isHost: StateFlow<Boolean> = _isHost.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _users = MutableStateFlow<List<JamUser>>(emptyList())
    val users: StateFlow<List<JamUser>> = _users.asStateFlow()

    private val _currentSong = MutableStateFlow<JamSong?>(null)
    val currentSong: StateFlow<JamSong?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _reactions = MutableStateFlow<List<JamReaction>>(emptyList())
    val reactions: StateFlow<List<JamReaction>> = _reactions.asStateFlow()

    private val _syncStatus = MutableStateFlow("Idle")
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()

    // Host Server State
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val clientWriters = Collections.synchronizedList(mutableListOf<PrintWriter>())

    // Client Connection State
    private var clientSocket: Socket? = null
    private var clientWriter: PrintWriter? = null
    private var clientJob: Job? = null

    // Playback callbacks hooked to MusicViewModel / ExoPlayer
    var onRemoteSeek: ((Long) -> Unit)? = null
    var onRemotePlay: (() -> Unit)? = null
    var onRemotePause: (() -> Unit)? = null
    var onRemoteTrackChange: ((JamSong) -> Unit)? = null
    var getCurrentPositionMs: (() -> Long)? = null

    /**
     * Flag to prevent programmatic updates from re-triggering outgoing broadcasts (Infinite Loop Guard)
     */
    var isProgrammaticAction: Boolean = false

    /**
     * Start a new Jam Session as HOST.
     * Starts embedded socket server on port 8990 and generates 6-character room code.
     */
    fun createRoom(context: Context, hostName: String, initialSong: Song?) {
        leaveRoom()

        scope.launch {
            try {
                serverSocket = ServerSocket(DEFAULT_PORT)
                val localIp = getLocalIpAddress(context) ?: "192.168.43.1"
                val generatedCode = encodeIpToRoomCode(localIp)

                _roomCode.value = generatedCode
                _isHost.value = true
                _isConnected.value = true
                _syncStatus.value = "Hosting Session"

                val hostUser = JamUser(
                    id = UUID.randomUUID().toString().take(8),
                    name = hostName.ifBlank { "Host" },
                    isHost = true
                )
                _users.value = listOf(hostUser)

                initialSong?.let { s ->
                    _currentSong.value = JamSong(
                        id = s.id.toString(),
                        title = s.title,
                        artist = s.artist,
                        album = s.album,
                        durationMs = s.duration
                    )
                }

                Log.d(TAG, "Jam Session started! Code: $generatedCode (IP: $localIp:$DEFAULT_PORT)")

                // Accept incoming listener connections
                serverJob = launch {
                    while (isActive) {
                        try {
                            val socket = serverSocket?.accept() ?: break
                            launch { handleConnectedClient(socket) }
                        } catch (e: Exception) {
                            if (!isActive) break
                            Log.e(TAG, "Error accepting client: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Jam server: ${e.message}", e)
                _syncStatus.value = "Failed to start: ${e.message}"
            }
        }
    }

    /**
     * JOIN an existing Jam Session as LISTENER.
     */
    fun joinRoom(code: String, listenerName: String) {
        leaveRoom()

        scope.launch {
            val cleanCode = code.trim().uppercase()
            _syncStatus.value = "Connecting to $cleanCode..."

            val targetIp = decodeRoomCodeToIp(cleanCode)
            Log.d(TAG, "Joining room $cleanCode -> target IP: $targetIp:$DEFAULT_PORT")

            try {
                val socket = Socket(targetIp, DEFAULT_PORT)
                socket.tcpNoDelay = true
                clientSocket = socket

                val writer = PrintWriter(socket.getOutputStream(), true)
                clientWriter = writer
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                _roomCode.value = cleanCode
                _isHost.value = false
                _isConnected.value = true
                _syncStatus.value = "Connected"

                // Send join greeting
                val joinMsg = JSONObject().apply {
                    put("type", "join")
                    put("userName", listenerName.ifBlank { "Listener" })
                }
                writer.println(joinMsg.toString())

                clientJob = launch {
                    try {
                        while (isActive) {
                            val line = reader.readLine() ?: break
                            handleIncomingMessage(line)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Client read loop disconnected: ${e.message}")
                    } finally {
                        withContext(Dispatchers.Main) {
                            _isConnected.value = false
                            _syncStatus.value = "Disconnected"
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to join room $cleanCode: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    _syncStatus.value = "Connection failed. Ensure you are on the same Wi-Fi / Hotspot!"
                }
            }
        }
    }

    /**
     * Handle incoming client connection on Host server.
     */
    private suspend fun handleConnectedClient(socket: Socket) = withContext(Dispatchers.IO) {
        socket.tcpNoDelay = true
        val writer = PrintWriter(socket.getOutputStream(), true)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        clientWriters.add(writer)

        var clientUser: JamUser? = null

        try {
            // Send immediate sync state
            val currentPos = getCurrentPositionMs?.invoke() ?: 0L
            val initialSync = JSONObject().apply {
                put("type", "sync_state")
                put("roomId", _roomCode.value ?: "")
                put("hostId", "host")
                put("isPlaying", _isPlaying.value)
                put("positionMs", currentPos)
                put("serverTimestampMs", System.currentTimeMillis())
                _currentSong.value?.let { s ->
                    put("song", JSONObject().apply {
                        put("id", s.id)
                        put("title", s.title)
                        put("artist", s.artist)
                        put("album", s.album ?: "")
                        put("durationMs", s.durationMs)
                    })
                }
            }
            writer.println(initialSync.toString())

            while (isActive) {
                val line = reader.readLine() ?: break
                val json = JSONObject(line)
                when (json.optString("type")) {
                    "join" -> {
                        val name = json.optString("userName", "Listener")
                        clientUser = JamUser(
                            id = UUID.randomUUID().toString().take(8),
                            name = name,
                            isHost = false
                        )
                        _users.value = _users.value + clientUser
                        broadcastUserList()
                    }
                    "reaction" -> {
                        val emoji = json.optString("emoji", "❤️")
                        val senderName = json.optString("userName", "Listener")
                        handleReaction(emoji, senderName)
                        broadcastRaw(line)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client disconnected: ${e.message}")
        } finally {
            clientWriters.remove(writer)
            clientUser?.let { u ->
                _users.value = _users.value.filter { it.id != u.id }
                broadcastUserList()
            }
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * Parse and apply incoming messages from the Host.
     */
    private fun handleIncomingMessage(message: String) {
        try {
            val json = JSONObject(message)
            when (json.optString("type")) {
                "sync_state" -> {
                    val isPlayingRemote = json.optBoolean("isPlaying", false)
                    val remotePosMs = json.optLong("positionMs", 0L)
                    val serverTimestampMs = json.optLong("serverTimestampMs", System.currentTimeMillis())

                    val songObj = json.optJSONObject("song")
                    val jamSong = if (songObj != null) {
                        JamSong(
                            id = songObj.optString("id", ""),
                            title = songObj.optString("title", ""),
                            artist = songObj.optString("artist", ""),
                            album = songObj.optString("album", ""),
                            durationMs = songObj.optLong("durationMs", 0L)
                        )
                    } else null

                    scope.launch(Dispatchers.Main) {
                        applySyncState(jamSong, isPlayingRemote, remotePosMs, serverTimestampMs)
                    }
                }
                "user_list" -> {
                    val arr = json.optJSONArray("users")
                    if (arr != null) {
                        val list = mutableListOf<JamUser>()
                        for (i in 0 until arr.length()) {
                            val u = arr.getJSONObject(i)
                            list.add(JamUser(u.getString("id"), u.getString("name"), u.getBoolean("isHost")))
                        }
                        _users.value = list
                    }
                }
                "reaction" -> {
                    val emoji = json.optString("emoji", "❤️")
                    val name = json.optString("userName", "Friend")
                    handleReaction(emoji, name)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: ${e.message}", e)
        }
    }

    /**
     * LATENCY & DRIFT CORRECTION (> 1.2s threshold)
     */
    private fun applySyncState(
        remoteSong: JamSong?,
        isPlayingRemote: Boolean,
        remotePosMs: Long,
        serverTimestampMs: Long
    ) {
        // Track change
        if (remoteSong != null && _currentSong.value?.title != remoteSong.title) {
            _currentSong.value = remoteSong
            isProgrammaticAction = true
            onRemoteTrackChange?.invoke(remoteSong)
        }

        // Latency calculation
        val networkDelayMs = (System.currentTimeMillis() - serverTimestampMs).coerceAtLeast(0L)
        val targetPosMs = remotePosMs + (if (isPlayingRemote) networkDelayMs else 0L)
        val localPosMs = getCurrentPositionMs?.invoke() ?: 0L
        val driftMs = Math.abs(localPosMs - targetPosMs)

        // Only seek if drift exceeds 1.2 seconds to prevent audio stuttering
        if (driftMs > DRIFT_THRESHOLD_MS) {
            Log.d(TAG, "Drift: ${driftMs}ms > 1200ms threshold. Correcting to ${targetPosMs}ms")
            isProgrammaticAction = true
            onRemoteSeek?.invoke(targetPosMs)
            _syncStatus.value = "⚡ Resynced (drift corrected)"
        } else {
            _syncStatus.value = "⚡ Perfectly Synced"
        }

        // Play / Pause sync
        if (isPlayingRemote && !_isPlaying.value) {
            _isPlaying.value = true
            isProgrammaticAction = true
            onRemotePlay?.invoke()
        } else if (!isPlayingRemote && _isPlaying.value) {
            _isPlaying.value = false
            isProgrammaticAction = true
            onRemotePause?.invoke()
        }
    }

    /**
     * Broadcast host playback action to all connected listeners.
     */
    fun broadcastPlaybackState(isPlaying: Boolean, positionMs: Long, song: Song?) {
        if (!_isHost.value || _roomCode.value == null) return
        if (isProgrammaticAction) {
            isProgrammaticAction = false
            return
        }

        _isPlaying.value = isPlaying
        song?.let { s ->
            _currentSong.value = JamSong(s.id.toString(), s.title, s.artist, s.album, s.duration)
        }

        val syncPayload = JSONObject().apply {
            put("type", "sync_state")
            put("roomId", _roomCode.value)
            put("hostId", "host")
            put("isPlaying", isPlaying)
            put("positionMs", positionMs)
            put("serverTimestampMs", System.currentTimeMillis())
            _currentSong.value?.let { s ->
                put("song", JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("artist", s.artist)
                    put("album", s.album ?: "")
                    put("durationMs", s.durationMs)
                })
            }
        }
        broadcastRaw(syncPayload.toString())
    }

    /**
     * Send live reaction emoji to room participants.
     */
    fun sendReaction(emoji: String, userName: String) {
        val payload = JSONObject().apply {
            put("type", "reaction")
            put("emoji", emoji)
            put("userName", userName)
            put("timestamp", System.currentTimeMillis())
        }

        handleReaction(emoji, userName)

        if (_isHost.value) {
            broadcastRaw(payload.toString())
        } else {
            scope.launch(Dispatchers.IO) {
                try {
                    clientWriter?.println(payload.toString())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send reaction: ${e.message}")
                }
            }
        }
    }

    private fun handleReaction(emoji: String, userName: String) {
        val reaction = JamReaction(
            id = UUID.randomUUID().toString(),
            emoji = emoji,
            userName = userName
        )
        _reactions.value = _reactions.value + reaction
        scope.launch {
            delay(3500)
            _reactions.value = _reactions.value.filter { it.id != reaction.id }
        }
    }

    private fun broadcastRaw(message: String) {
        scope.launch(Dispatchers.IO) {
            synchronized(clientWriters) {
                clientWriters.forEach { writer ->
                    try { writer.println(message) } catch (_: Exception) {}
                }
            }
        }
    }

    private fun broadcastUserList() {
        val payload = JSONObject().apply {
            put("type", "user_list")
            val arr = org.json.JSONArray()
            _users.value.forEach { u ->
                arr.put(JSONObject().apply {
                    put("id", u.id)
                    put("name", u.name)
                    put("isHost", u.isHost)
                })
            }
            put("users", arr)
        }
        broadcastRaw(payload.toString())
    }

    /**
     * Leave or terminate session.
     */
    fun leaveRoom() {
        serverJob?.cancel()
        clientJob?.cancel()

        try { serverSocket?.close() } catch (_: Exception) {}
        try { clientSocket?.close() } catch (_: Exception) {}

        serverSocket = null
        clientSocket = null
        clientWriter = null
        clientWriters.clear()

        _roomCode.value = null
        _isHost.value = false
        _isConnected.value = false
        _users.value = emptyList()
        _reactions.value = emptyList()
        _syncStatus.value = "Idle"
        isProgrammaticAction = false
    }

    // Helper: Encode IP Address to 6-char Room Code (e.g. 192.168.43.1 -> MR08IT)
    private fun encodeIpToRoomCode(ip: String): String {
        return try {
            val parts = ip.split(".").map { it.toInt() }
            if (parts.size == 4) {
                val num = (parts[2] shl 8) or parts[3]
                val hexCode = num.toString(36).uppercase().padStart(4, '0')
                "MR$hexCode"
            } else {
                "MR9999"
            }
        } catch (_: Exception) {
            "MR8888"
        }
    }

    // Helper: Decode 6-char Room Code back to local IP (e.g. MR08IT -> 192.168.43.1)
    private fun decodeRoomCodeToIp(code: String): String {
        return try {
            val clean = code.removePrefix("MR").lowercase()
            val num = clean.toInt(36)
            val b3 = (num shr 8) and 0xFF
            val b4 = num and 0xFF
            "192.168.$b3.$b4"
        } catch (_: Exception) {
            "192.168.43.1"
        }
    }

    // Helper: Get device's Wi-Fi or Hotspot IP address
    private fun getLocalIpAddress(context: Context): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val host = addr.hostAddress
                        if (host != null && host.startsWith("192.168.")) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "192.168.43.1"
    }
}
