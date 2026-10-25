/**
 * MR Music - Listen Together (Jam Session) Sync Server
 * Developed and engineered by Mohanraj.MS (MR Music Studio)
 * 
 * Stack: Node.js, Express, Socket.io
 */

const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const cors = require('cors');

const app = express();
app.use(cors());
app.use(express.json());

const server = http.createServer(app);
const io = new Server(server, {
  cors: {
    origin: '*',
    methods: ['GET', 'POST']
  },
  pingInterval: 10000,
  pingTimeout: 5000
});

// In-Memory State
// rooms: Map<roomId, RoomState>
const rooms = new Map();
// userRooms: Map<socketId, { roomId, userId, userName }>
const userRooms = new Map();

/**
 * Generate a unique 6-character uppercase alphanumeric room code.
 * (Excludes visually ambiguous characters: 0, O, 1, I, L)
 */
function generateRoomCode() {
  const chars = '23456789ABCDEFGHJKMNPQRSTUVWXYZ';
  let code = '';
  do {
    code = '';
    for (let i = 0; i < 6; i++) {
      code += chars.charAt(Math.floor(Math.random() * chars.length));
    }
  } while (rooms.has(code));
  return code;
}

/**
 * Calculate the exact current playback position accounting for elapsed time.
 */
function calculateCurrentPosition(room) {
  if (!room.isPlaying) {
    return room.lastPosition;
  }
  const elapsedSeconds = (Date.now() - room.updatedAt) / 1000;
  const estimated = room.lastPosition + elapsedSeconds;

  if (room.currentSong && room.currentSong.duration > 0) {
    return Math.min(estimated, room.currentSong.duration);
  }
  return Math.max(0, estimated);
}

/**
 * Broadcast current sync state to a room.
 */
function broadcastSyncState(roomId, actionType = 'sync', senderId = null) {
  const room = rooms.get(roomId);
  if (!room) return;

  const currentPos = calculateCurrentPosition(room);
  const payload = {
    roomId,
    hostId: room.hostId,
    currentSong: room.currentSong,
    isPlaying: room.isPlaying,
    position: currentPos,
    serverTime: Date.now(),
    queue: room.queue,
    actionType,
    senderId
  };

  io.to(`room_${roomId}`).emit('sync_state', payload);
}

/**
 * Handle user leaving a room (explicitly or via disconnection).
 */
function handleUserLeave(socket) {
  const userRecord = userRooms.get(socket.id);
  if (!userRecord) return;

  const { roomId, userId, userName } = userRecord;
  userRooms.delete(socket.id);
  socket.leave(`room_${roomId}`);

  const room = rooms.get(roomId);
  if (!room) return;

  // Remove user from room user list
  room.users = room.users.filter(u => u.socketId !== socket.id);

  console.log(`[Jam] User ${userName} (${socket.id}) left room ${roomId}. Remaining: ${room.users.length}`);

  if (room.users.length === 0) {
    // Room is empty -> Clean up
    rooms.delete(roomId);
    console.log(`[Jam] Room ${roomId} deleted (empty).`);
    return;
  }

  // If the leaving user was the host, migrate host privileges to the next oldest user
  if (room.hostId === socket.id) {
    const newHost = room.users[0];
    room.hostId = newHost.socketId;
    newHost.isHost = true;

    console.log(`[Jam] Host migrated to ${newHost.userName} (${newHost.socketId}) in room ${roomId}`);

    io.to(`room_${roomId}`).emit('host_changed', {
      newHostId: newHost.socketId,
      newHostName: newHost.userName,
      users: room.users
    });
  }

  // Notify remaining users
  io.to(`room_${roomId}`).emit('user_left', {
    userId,
    userName,
    users: room.users,
    totalListeners: room.users.length
  });
}

// Socket.io Connection & Events
io.on('connection', (socket) => {
  console.log(`[Jam] Client connected: ${socket.id}`);

  /**
   * 1. CREATE ROOM
   */
  socket.on('create_room', (data = {}) => {
    // Leave any existing room first
    handleUserLeave(socket);

    const roomId = generateRoomCode();
    const userName = (data.userName || 'Host').trim().substring(0, 30);
    const initialSong = data.initialSong || null;

    const userObj = {
      socketId: socket.id,
      userId: socket.id,
      userName,
      avatar: data.avatar || null,
      isHost: true,
      joinedAt: Date.now()
    };

    const newRoom = {
      roomId,
      hostId: socket.id,
      users: [userObj],
      currentSong: initialSong,
      queue: initialSong ? [initialSong] : [],
      isPlaying: false,
      lastPosition: 0,
      updatedAt: Date.now(),
      createdAt: Date.now()
    };

    rooms.set(roomId, newRoom);
    userRooms.set(socket.id, { roomId, userId: socket.id, userName });
    socket.join(`room_${roomId}`);

    console.log(`[Jam] Room ${roomId} created by ${userName} (${socket.id})`);

    socket.emit('room_created', {
      roomId,
      isHost: true,
      user: userObj,
      roomState: {
        roomId,
        hostId: socket.id,
        users: newRoom.users,
        currentSong: newRoom.currentSong,
        isPlaying: newRoom.isPlaying,
        position: 0,
        serverTime: Date.now(),
        queue: newRoom.queue
      }
    });
  });

  /**
   * 2. JOIN ROOM
   */
  socket.on('join_room', (data = {}) => {
    const rawCode = (data.roomId || '').trim().toUpperCase();
    const userName = (data.userName || 'Listener').trim().substring(0, 30);

    const room = rooms.get(rawCode);
    if (!room) {
      socket.emit('error_message', {
        code: 'ROOM_NOT_FOUND',
        message: `Room "${rawCode}" not found or expired. Check the 6-character code.`
      });
      return;
    }

    // Leave any previous room
    handleUserLeave(socket);

    const userObj = {
      socketId: socket.id,
      userId: socket.id,
      userName,
      avatar: data.avatar || null,
      isHost: false,
      joinedAt: Date.now()
    };

    room.users.push(userObj);
    userRooms.set(socket.id, { roomId: rawCode, userId: socket.id, userName });
    socket.join(`room_${rawCode}`);

    // Exact playback position calculated using elapsed time
    const currentPosition = calculateCurrentPosition(room);

    console.log(`[Jam] ${userName} (${socket.id}) joined room ${rawCode}. Total: ${room.users.length}`);

    // Send confirmation & full sync_state to the newly joined user
    socket.emit('room_joined', {
      roomId: rawCode,
      isHost: false,
      user: userObj,
      users: room.users,
      syncState: {
        roomId: rawCode,
        hostId: room.hostId,
        currentSong: room.currentSong,
        isPlaying: room.isPlaying,
        position: currentPosition,
        serverTime: Date.now(),
        queue: room.queue
      }
    });

    // Notify other room listeners
    socket.to(`room_${rawCode}`).emit('user_joined', {
      user: userObj,
      users: room.users,
      totalListeners: room.users.length
    });
  });

  /**
   * 3. PLAYBACK ACTION (play, pause, seek, track_change)
   */
  socket.on('playback_action', (data = {}) => {
    const userRecord = userRooms.get(socket.id);
    if (!userRecord) return;

    const { roomId } = userRecord;
    const room = rooms.get(roomId);
    if (!room) return;

    // By default, Host controls playback (or any member in open jam sessions)
    const isHost = (room.hostId === socket.id);
    if (!isHost && data.requireHost !== false) {
      socket.emit('error_message', {
        code: 'PERMISSION_DENIED',
        message: 'Only the Host can control playback in this Jam Session.'
      });
      return;
    }

    const { type, position, track } = data;
    const now = Date.now();

    switch (type) {
      case 'play': {
        const currentPos = (typeof position === 'number') ? position : calculateCurrentPosition(room);
        room.lastPosition = currentPos;
        room.isPlaying = true;
        room.updatedAt = now;
        console.log(`[Jam] Room ${roomId} PLAY at ${currentPos}s`);
        break;
      }
      case 'pause': {
        const currentPos = (typeof position === 'number') ? position : calculateCurrentPosition(room);
        room.lastPosition = currentPos;
        room.isPlaying = false;
        room.updatedAt = now;
        console.log(`[Jam] Room ${roomId} PAUSE at ${currentPos}s`);
        break;
      }
      case 'seek': {
        if (typeof position === 'number') {
          room.lastPosition = Math.max(0, position);
          room.updatedAt = now;
          console.log(`[Jam] Room ${roomId} SEEK to ${position}s`);
        }
        break;
      }
      case 'track_change': {
        if (track) {
          room.currentSong = track;
          room.lastPosition = 0;
          room.isPlaying = true;
          room.updatedAt = now;

          // Add to queue if not present
          if (!room.queue.some(t => t.id === track.id)) {
            room.queue.push(track);
          }
          console.log(`[Jam] Room ${roomId} TRACK_CHANGE: ${track.title} - ${track.artist}`);
        }
        break;
      }
      default:
        console.warn(`[Jam] Unknown action type: ${type}`);
        return;
    }

    // Broadcast updated sync_state to all room participants
    broadcastSyncState(roomId, type, socket.id);
  });

  /**
   * 4. ADD TO QUEUE
   */
  socket.on('add_to_queue', (data = {}) => {
    const userRecord = userRooms.get(socket.id);
    if (!userRecord) return;

    const { roomId } = userRecord;
    const room = rooms.get(roomId);
    if (!room || !data.track) return;

    room.queue.push(data.track);

    // If no song is currently playing, start playing this track immediately
    if (!room.currentSong) {
      room.currentSong = data.track;
      room.lastPosition = 0;
      room.isPlaying = true;
      room.updatedAt = Date.now();
      broadcastSyncState(roomId, 'track_change', socket.id);
    } else {
      io.to(`room_${roomId}`).emit('queue_updated', {
        queue: room.queue,
        addedBy: userRecord.userName,
        track: data.track
      });
    }
  });

  /**
   * 5. SEND REACTION (Floating Emojis)
   */
  socket.on('send_reaction', (data = {}) => {
    const userRecord = userRooms.get(socket.id);
    if (!userRecord) return;

    const { roomId, userName } = userRecord;
    const emoji = (data.emoji || '❤️').substring(0, 4);

    const reactionPayload = {
      id: `${Date.now()}_${Math.random().toString(36).substring(2, 7)}`,
      emoji,
      userName: data.userName || userName,
      userId: socket.id,
      timestamp: Date.now()
    };

    io.to(`room_${roomId}`).emit('reaction_received', reactionPayload);
  });

  /**
   * 6. LEAVE ROOM (Explicit)
   */
  socket.on('leave_room', () => {
    handleUserLeave(socket);
  });

  /**
   * 7. DISCONNECT
   */
  socket.on('disconnect', () => {
    console.log(`[Jam] Client disconnected: ${socket.id}`);
    handleUserLeave(socket);
  });
});

// HTTP Endpoints
app.get('/health', (req, res) => {
  res.json({
    status: 'ok',
    service: 'MR Music Listen Together Server',
    activeRooms: rooms.size,
    timestamp: Date.now()
  });
});

app.get('/rooms/:roomId', (req, res) => {
  const roomId = req.params.roomId.toUpperCase();
  const room = rooms.get(roomId);
  if (!room) {
    return res.status(404).json({ error: 'Room not found' });
  }

  res.json({
    roomId: room.roomId,
    hostId: room.hostId,
    listenersCount: room.users.length,
    users: room.users.map(u => ({ id: u.userId, name: u.userName, isHost: u.isHost })),
    currentSong: room.currentSong,
    isPlaying: room.isPlaying,
    currentPosition: calculateCurrentPosition(room)
  });
});

const PORT = process.env.PORT || 4000;
server.listen(PORT, () => {
  console.log(`===============================================`);
  console.log(`🎵 MR Music Listen Together Server Running!`);
  console.log(`📡 Port: ${PORT}`);
  console.log(`⚡ Socket.io Ready for Real-Time Sync`);
  console.log(`===============================================`);
});
