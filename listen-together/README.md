# 🎵 MR Music - Listen Together (Jam Session)

Engineered by **Mohanraj.MS** (**MR Music Studio**).

Real-time synchronized music listening architecture using **Node.js + Express + Socket.io** on the backend, and **React + Custom Hook + Drift Correction** on the client.

---

## 🚀 1. Backend Server Setup

```bash
cd listen-together/server
npm install
npm start
```

Runs by default on port `4000` (configurable via `PORT=4000`).

### Features:
- **6-Character Room Codes:** Unique, unambiguous room IDs (e.g. `K7P92X`).
- **In-Memory Room Management:** Stored via `Map<roomId, RoomState>`.
- **Elapsed Time Position Calculation:** Computes `lastPosition + (Date.now() - updatedAt) / 1000` when a listener joins an ongoing track.
- **Graceful Host Migration:** When the host leaves or disconnects, host authority smoothly transfers to the next oldest listener.
- **Floating Emoji Reactions:** Real-time broadcast of animated reactions to all room listeners.

---

## 🎧 2. Frontend Integration

### Basic Usage with `<audio>` element:

```tsx
import React, { useRef, useState } from 'react';
import { useListenTogether } from './client/useListenTogether';
import { ListenTogetherModal } from './client/ListenTogetherModal';

export const MusicPlayerApp = () => {
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const [isJamModalOpen, setIsJamModalOpen] = useState(false);

  const jam = useListenTogether(audioRef, {
    serverUrl: 'http://localhost:4000',
    userName: 'Mohanraj',
    autoConnect: true
  });

  return (
    <div>
      {/* Audio Element with infinite-loop prevention bindings */}
      <audio
        ref={audioRef}
        controls
        onPlay={jam.audioHandlers.onPlay}
        onPause={jam.audioHandlers.onPause}
        onSeeked={jam.audioHandlers.onSeeked}
      />

      <button onClick={() => setIsJamModalOpen(true)}>
        📻 Listen Together {jam.roomId && `(${jam.users.length})`}
      </button>

      {/* Listen Together Modal / Drawer */}
      <ListenTogetherModal
        isOpen={isJamModalOpen}
        onClose={() => setIsJamModalOpen(false)}
        jam={jam}
        defaultUserName="Mohanraj"
      />
    </div>
  );
};
```

### Key Technical Safeguards:
1. **Latency & Drift Correction:** Only performs `audio.currentTime = targetTime` if drift exceeds **1.2 seconds**, eliminating micro-stutters and buffer interruptions.
2. **Infinite Loop Prevention:** `isProgrammaticUpdate` ref flag prevents native audio events triggered by socket sync from firing recursive outgoing socket emits.
