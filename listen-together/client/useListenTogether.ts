/**
 * MR Music - Listen Together Client Sync Hook
 * Developed and engineered by Mohanraj.MS (MR Music Studio)
 * 
 * Reusable React Hook with:
 * - Exact Latency & Network Drift Correction (> 1.2s threshold)
 * - Infinite Loop Prevention using programmatic lock flags
 * - Audio Element Control & Real-time State Management
 */

import { useState, useEffect, useRef, useCallback } from 'react';
import { io, Socket } from 'socket.io-client';

export interface SongMetadata {
  id: string;
  title: string;
  artist: string;
  album?: string;
  artworkUri?: string;
  streamUrl: string;
  duration: number; // in seconds
}

export interface RoomUser {
  socketId: string;
  userId: string;
  userName: string;
  avatar?: string | null;
  isHost: boolean;
}

export interface FloatingReaction {
  id: string;
  emoji: string;
  userName: string;
  timestamp: number;
}

export interface SyncStatePayload {
  roomId: string;
  hostId: string;
  currentSong: SongMetadata | null;
  isPlaying: boolean;
  position: number;
  serverTime: number;
  queue: SongMetadata[];
  actionType?: string;
  senderId?: string;
}

interface UseListenTogetherOptions {
  serverUrl?: string;
  userName?: string;
  autoConnect?: boolean;
}

export function useListenTogether(
  audioRef: React.RefObject<HTMLAudioElement | null>,
  options: UseListenTogetherOptions = {}
) {
  const {
    serverUrl = 'http://localhost:4000',
    userName = 'Listener',
    autoConnect = false
  } = options;

  // Connection & Room State
  const [isConnected, setIsConnected] = useState<boolean>(false);
  const [roomId, setRoomId] = useState<string | null>(null);
  const [isHost, setIsHost] = useState<boolean>(false);
  const [users, setUsers] = useState<RoomUser[]>([]);
  const [currentSong, setCurrentSong] = useState<SongMetadata | null>(null);
  const [isPlaying, setIsPlaying] = useState<boolean>(false);
  const [queue, setQueue] = useState<SongMetadata[]>([]);
  const [reactions, setReactions] = useState<FloatingReaction[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [syncStatus, setSyncStatus] = useState<'idle' | 'syncing' | 'synced' | 'drift_corrected'>('idle');

  // Socket instance ref
  const socketRef = useRef<Socket | null>(null);

  /**
   * CRITICAL: Infinite Event Loop Prevention Flag
   * When true, audio element's onPlay, onPause, or onSeeked events
   * will NOT emit duplicate socket messages back to the server!
   */
  const isProgrammaticUpdate = useRef<boolean>(false);

  // Initialize Socket Connection
  useEffect(() => {
    if (!autoConnect && !roomId) return;

    if (!socketRef.current) {
      const socket = io(serverUrl, {
        transports: ['websocket', 'polling'],
        reconnection: true,
        reconnectionAttempts: 10,
        reconnectionDelay: 1000
      });

      socketRef.current = socket;

      socket.on('connect', () => {
        setIsConnected(true);
        setError(null);
        console.log('[Jam Client] Connected to sync server:', socket.id);
      });

      socket.on('disconnect', () => {
        setIsConnected(false);
        setSyncStatus('idle');
        console.log('[Jam Client] Disconnected from sync server');
      });

      socket.on('connect_error', (err) => {
        setError(`Connection failed: ${err.message}`);
        console.error('[Jam Client] Connection error:', err);
      });

      // Room Created Event
      socket.on('room_created', (data: { roomId: string; isHost: boolean; roomState: SyncStatePayload & { users: RoomUser[] } }) => {
        setRoomId(data.roomId);
        setIsHost(true);
        setUsers(data.roomState.users);
        setCurrentSong(data.roomState.currentSong);
        setIsPlaying(data.roomState.isPlaying);
        setQueue(data.roomState.queue);
        setError(null);
      });

      // Room Joined Event
      socket.on('room_joined', (data: { roomId: string; isHost: boolean; users: RoomUser[]; syncState: SyncStatePayload }) => {
        setRoomId(data.roomId);
        setIsHost(data.isHost);
        setUsers(data.users);
        applySyncState(data.syncState);
        setError(null);
      });

      // Sync State Broadcast Event
      socket.on('sync_state', (syncData: SyncStatePayload) => {
        // If this client sent the action, we already updated locally
        if (syncData.senderId === socket.id) return;
        applySyncState(syncData);
      });

      // User Joined / Left Events
      socket.on('user_joined', (data: { user: RoomUser; users: RoomUser[] }) => {
        setUsers(data.users);
      });

      socket.on('user_left', (data: { userId: string; userName: string; users: RoomUser[] }) => {
        setUsers(data.users);
      });

      // Host Changed Event
      socket.on('host_changed', (data: { newHostId: string; newHostName: string; users: RoomUser[] }) => {
        setUsers(data.users);
        setIsHost(data.newHostId === socket.id);
      });

      // Queue Updated Event
      socket.on('queue_updated', (data: { queue: SongMetadata[] }) => {
        setQueue(data.queue);
      });

      // Live Floating Reaction Event
      socket.on('reaction_received', (reaction: FloatingReaction) => {
        setReactions((prev) => [...prev, reaction]);
        // Auto-remove reaction after 4 seconds
        setTimeout(() => {
          setReactions((prev) => prev.filter((r) => r.id !== reaction.id));
        }, 4000);
      });

      // Error Event
      socket.on('error_message', (err: { message: string }) => {
        setError(err.message);
      });
    }

    return () => {
      // Cleanup on unmount
      if (socketRef.current) {
        socketRef.current.disconnect();
        socketRef.current = null;
      }
    };
  }, [serverUrl, autoConnect]);

  /**
   * Apply Sync State with Latency & Drift Correction
   */
  const applySyncState = useCallback(
    (syncData: SyncStatePayload) => {
      const audio = audioRef.current;
      if (!audio) return;

      setSyncStatus('syncing');

      // 1. Calculate Estimated Network Latency
      const networkLatencySeconds = Math.max(0, (Date.now() - syncData.serverTime) / 1000);

      // 2. Compute exact Target Time
      let targetTime = syncData.position;
      if (syncData.isPlaying) {
        targetTime += networkLatencySeconds;
      }

      // Update Track if Changed
      if (syncData.currentSong && (!currentSong || currentSong.id !== syncData.currentSong.id)) {
        setCurrentSong(syncData.currentSong);
        isProgrammaticUpdate.current = true;
        audio.src = syncData.currentSong.streamUrl;
        audio.load();
      }

      setCurrentSong(syncData.currentSong);
      setQueue(syncData.queue);
      setIsPlaying(syncData.isPlaying);

      // 3. LATENCY & DRIFT CORRECTION (> 1.2s threshold)
      const currentLocalTime = audio.currentTime;
      const drift = Math.abs(currentLocalTime - targetTime);

      if (drift > 1.2) {
        console.log(`[Jam Sync] Drift detected: ${drift.toFixed(2)}s > 1.2s threshold. Correcting to ${targetTime.toFixed(2)}s`);
        isProgrammaticUpdate.current = true;
        audio.currentTime = Math.max(0, targetTime);
        setSyncStatus('drift_corrected');
      } else {
        setSyncStatus('synced');
      }

      // 4. Play / Pause Synchronization
      if (syncData.isPlaying && audio.paused) {
        isProgrammaticUpdate.current = true;
        const playPromise = audio.play();
        if (playPromise !== undefined) {
          playPromise.catch((e) => {
            console.warn('[Jam Sync] Autoplay blocked, user interaction required:', e);
          });
        }
      } else if (!syncData.isPlaying && !audio.paused) {
        isProgrammaticUpdate.current = true;
        audio.pause();
      }
    },
    [audioRef, currentSong]
  );

  /**
   * Ensure socket connection is active
   */
  const ensureSocket = useCallback((): Socket => {
    if (!socketRef.current) {
      const socket = io(serverUrl, {
        transports: ['websocket', 'polling']
      });
      socketRef.current = socket;
    }
    return socketRef.current;
  }, [serverUrl]);

  /**
   * Public API: Create Room (Host)
   */
  const createRoom = useCallback(
    (name: string = userName, initialSong: SongMetadata | null = currentSong) => {
      const socket = ensureSocket();
      socket.emit('create_room', { userName: name, initialSong });
    },
    [ensureSocket, userName, currentSong]
  );

  /**
   * Public API: Join Room (Listener)
   */
  const joinRoom = useCallback(
    (targetRoomId: string, name: string = userName) => {
      const socket = ensureSocket();
      const cleanCode = targetRoomId.trim().toUpperCase();
      socket.emit('join_room', { roomId: cleanCode, userName: name });
    },
    [ensureSocket, userName]
  );

  /**
   * Public API: Leave Room
   */
  const leaveRoom = useCallback(() => {
    if (socketRef.current) {
      socketRef.current.emit('leave_room');
    }
    setRoomId(null);
    setIsHost(false);
    setUsers([]);
    setQueue([]);
    setSyncStatus('idle');
  }, []);

  /**
   * Public API: Playback Action
   * (Emits to room if host or permitted, skipping if triggered programmatically)
   */
  const emitPlaybackAction = useCallback(
    (type: 'play' | 'pause' | 'seek' | 'track_change', position?: number, track?: SongMetadata) => {
      if (!roomId || !socketRef.current) return;
      if (!isHost) return; // Host controls playback in structured jams

      const audio = audioRef.current;
      const currentPos = position !== undefined ? position : (audio ? audio.currentTime : 0);

      socketRef.current.emit('playback_action', {
        type,
        position: currentPos,
        track: track || currentSong
      });
    },
    [roomId, isHost, audioRef, currentSong]
  );

  /**
   * Audio Event Handlers to attach to <audio> element
   * These prevent recursive/infinite event loops!
   */
  const handleAudioPlay = useCallback(() => {
    if (isProgrammaticUpdate.current) {
      isProgrammaticUpdate.current = false;
      return;
    }
    if (isHost && roomId) {
      emitPlaybackAction('play');
    }
  }, [isHost, roomId, emitPlaybackAction]);

  const handleAudioPause = useCallback(() => {
    if (isProgrammaticUpdate.current) {
      isProgrammaticUpdate.current = false;
      return;
    }
    if (isHost && roomId) {
      emitPlaybackAction('pause');
    }
  }, [isHost, roomId, emitPlaybackAction]);

  const handleAudioSeeked = useCallback(() => {
    if (isProgrammaticUpdate.current) {
      isProgrammaticUpdate.current = false;
      return;
    }
    if (isHost && roomId && audioRef.current) {
      emitPlaybackAction('seek', audioRef.current.currentTime);
    }
  }, [isHost, roomId, audioRef, emitPlaybackAction]);

  /**
   * Public API: Add Track to Queue
   */
  const addToQueue = useCallback(
    (track: SongMetadata) => {
      if (!socketRef.current || !roomId) return;
      socketRef.current.emit('add_to_queue', { track });
    },
    [roomId]
  );

  /**
   * Public API: Send Live Floating Reaction
   */
  const sendReaction = useCallback(
    (emoji: string) => {
      if (!socketRef.current || !roomId) return;
      socketRef.current.emit('send_reaction', { emoji, userName });
    },
    [roomId, userName]
  );

  return {
    // State
    isConnected,
    roomId,
    isHost,
    users,
    currentSong,
    isPlaying,
    queue,
    reactions,
    syncStatus,
    error,
    // Actions
    createRoom,
    joinRoom,
    leaveRoom,
    emitPlaybackAction,
    addToQueue,
    sendReaction,
    // Audio Element Event Binders (Prevents infinite loops)
    audioHandlers: {
      onPlay: handleAudioPlay,
      onPause: handleAudioPause,
      onSeeked: handleAudioSeeked
    }
  };
}
