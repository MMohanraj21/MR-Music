/**
 * MR Music - Listen Together (Jam Session) Modal Component
 * Developed and engineered by Mohanraj.MS (MR Music Studio)
 * 
 * Features:
 * - 6-Character Room Code Display & 1-Click Copy
 * - Real-time Active Listeners Pill & List
 * - Live Floating Emoji Reactions Layer & Reaction Picker Bar
 * - Host Badge & Playback Sync Status Indicator
 */

import React, { useState } from 'react';
import { useListenTogether } from './useListenTogether';
import './ListenTogether.css';

interface ListenTogetherModalProps {
  isOpen: boolean;
  onClose: () => void;
  jam: ReturnType<typeof useListenTogether>;
  defaultUserName?: string;
}

const REACTION_EMOJIS = ['❤️', '🔥', '👏', '🎉', '🚀', '🎵'];

export const ListenTogetherModal: React.FC<ListenTogetherModalProps> = ({
  isOpen,
  onClose,
  jam,
  defaultUserName = 'Music Fan'
}) => {
  const [userName, setUserName] = useState<string>(defaultUserName);
  const [inputCode, setInputCode] = useState<string>('');
  const [copied, setCopied] = useState<boolean>(false);

  if (!isOpen) return null;

  const handleCopyCode = async () => {
    if (!jam.roomId) return;
    try {
      await navigator.clipboard.writeText(jam.roomId);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (err) {
      console.error('Failed to copy code:', err);
    }
  };

  const handleCreateRoom = () => {
    jam.createRoom(userName.trim() || 'Host');
  };

  const handleJoinRoom = (e: React.FormEvent) => {
    e.preventDefault();
    if (!inputCode.trim()) return;
    jam.joinRoom(inputCode.trim().toUpperCase(), userName.trim() || 'Listener');
  };

  return (
    <div className="jam-overlay" onClick={onClose}>
      <div className="jam-modal" onClick={(e) => e.stopPropagation()}>
        {/* Live Floating Emoji Reactions Layer */}
        <div className="jam-reactions-container">
          {jam.reactions.map((reaction, index) => {
            // Distribute emojis horizontally across the modal
            const randomLeft = 15 + ((index * 23) % 70);
            return (
              <div
                key={reaction.id}
                className="jam-floating-emoji"
                style={{ left: `${randomLeft}%` }}
              >
                <span className="emoji">{reaction.emoji}</span>
                <span className="user-tag">{reaction.userName}</span>
              </div>
            );
          })}
        </div>

        {/* Modal Header */}
        <div className="jam-header">
          <div className="jam-title-group">
            <div className="jam-logo-badge">📻</div>
            <div>
              <h2>Listen Together</h2>
              <p>MR Music Real-Time Jam Session</p>
            </div>
          </div>
          <button className="jam-close-btn" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </div>

        {/* Modal Body */}
        <div className="jam-body">
          {jam.error && (
            <div style={{
              background: 'rgba(255, 69, 58, 0.15)',
              border: '1px solid rgba(255, 69, 58, 0.3)',
              borderRadius: '12px',
              padding: '10px 14px',
              color: '#ff453a',
              fontSize: '13px'
            }}>
              ⚠️ {jam.error}
            </div>
          )}

          {!jam.roomId ? (
            /* ================= VIEW A: Not in a Room (Join or Create) ================= */
            <>
              <div className="jam-input-group">
                <label className="jam-input-label">Your Display Name</label>
                <input
                  type="text"
                  className="jam-input"
                  placeholder="Enter your name"
                  value={userName}
                  maxLength={25}
                  onChange={(e) => setUserName(e.target.value)}
                />
              </div>

              {/* Host: Create Room */}
              <button className="jam-primary-btn" onClick={handleCreateRoom}>
                <span>✨</span> Start a New Jam Session
              </button>

              <div style={{
                display: 'flex',
                alignItems: 'center',
                gap: '12px',
                color: '#636366',
                fontSize: '12px',
                fontWeight: 600
              }}>
                <div style={{ flex: 1, height: '1px', background: 'rgba(255,255,255,0.08)' }} />
                OR JOIN WITH CODE
                <div style={{ flex: 1, height: '1px', background: 'rgba(255,255,255,0.08)' }} />
              </div>

              {/* Listener: Join Room */}
              <form onSubmit={handleJoinRoom} style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
                <input
                  type="text"
                  className="jam-input jam-input-code"
                  placeholder="6-CHAR CODE"
                  maxLength={6}
                  value={inputCode}
                  onChange={(e) => setInputCode(e.target.value.toUpperCase())}
                />
                <button
                  type="submit"
                  className="jam-secondary-btn"
                  disabled={inputCode.trim().length !== 6}
                >
                  🎧 Join Room
                </button>
              </form>
            </>
          ) : (
            /* ================= VIEW B: Active in a Room ================= */
            <>
              {/* Room Code Card */}
              <div className="jam-room-card">
                <span className="jam-room-badge">
                  {jam.isHost ? '👑 Host Session' : '🎧 Listening Mode'}
                </span>
                <div className="jam-room-code-display">
                  <span className="jam-code-text">{jam.roomId}</span>
                  <button
                    className={`jam-copy-btn ${copied ? 'copied' : ''}`}
                    onClick={handleCopyCode}
                  >
                    {copied ? '✓ Copied' : '📋 Copy'}
                  </button>
                </div>
              </div>

              {/* Active Listeners Section */}
              <div className="jam-listeners-section">
                <div className="jam-listeners-header">
                  <span>ACTIVE LISTENERS</span>
                  <div className="jam-listeners-pill">
                    <span className="jam-pulse-dot" />
                    <span>{jam.users.length} Online</span>
                  </div>
                </div>
                <div className="jam-users-list">
                  {jam.users.map((user) => (
                    <div
                      key={user.socketId}
                      className={`jam-user-chip ${user.isHost ? 'host' : ''}`}
                    >
                      <span>{user.isHost ? '👑' : '👤'}</span>
                      <span>{user.userName}</span>
                    </div>
                  ))}
                </div>
              </div>

              {/* Current Song Sync Status */}
              {jam.currentSong ? (
                <div className="jam-now-playing">
                  {jam.currentSong.artworkUri ? (
                    <img
                      src={jam.currentSong.artworkUri}
                      alt="Art"
                      className="jam-track-art"
                    />
                  ) : (
                    <div className="jam-track-art" style={{ display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                      🎵
                    </div>
                  )}
                  <div className="jam-track-info">
                    <div className="jam-track-title">{jam.currentSong.title}</div>
                    <div className="jam-track-artist">
                      {jam.currentSong.artist} • {jam.isPlaying ? '▶ Playing' : '⏸ Paused'}
                    </div>
                  </div>
                  <div style={{
                    fontSize: '11px',
                    fontWeight: 700,
                    color: jam.syncStatus === 'synced' ? '#34c759' : '#ff9500'
                  }}>
                    {jam.syncStatus === 'synced' ? '⚡ Synced' : '🔄 Syncing'}
                  </div>
                </div>
              ) : (
                <div style={{
                  padding: '12px',
                  borderRadius: '12px',
                  background: 'rgba(255,255,255,0.03)',
                  fontSize: '12px',
                  color: '#8e8e93',
                  textAlign: 'center'
                }}>
                  {jam.isHost ? 'Select a song in MR Music to start playback' : 'Waiting for Host to start a track...'}
                </div>
              )}

              {/* Quick Reactions Bar */}
              <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
                <span className="jam-input-label">Live Emoji Reactions</span>
                <div className="jam-reactions-bar">
                  {REACTION_EMOJIS.map((emoji) => (
                    <button
                      key={emoji}
                      className="jam-reaction-btn"
                      onClick={() => jam.sendReaction(emoji)}
                    >
                      {emoji}
                    </button>
                  ))}
                </div>
              </div>

              {/* Leave Button */}
              <button className="jam-danger-btn" onClick={jam.leaveRoom}>
                🚪 Leave Jam Session
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
};
