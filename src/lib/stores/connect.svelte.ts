import { invoke } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import type { Track } from '$lib/types';
import { player } from '$lib/stores/player.svelte';

export interface ConnectDevice {
  id: string | null;
  name: string;
  platform: string | null;
  addr: string | null;
  online: boolean;
  trusted: boolean;
  connection: string;
  last_error: string | null;
}

export interface PairingPrompt {
  prompt_id: string;
  incoming: boolean;
  peer_name: string;
  code: string;
  device_id: string | null;
}

export interface LocalDevice {
  id: string;
  name: string;
  platform: string;
  port: number;
  fingerprint: string;
}

export interface TrackMeta {
  id: string;
  title: string;
  artist: string;
  artists: { name: string; id?: string }[];
  album: { title: string; id?: string } | null;
  durationMs: number;
  artworkUrl: string | null;
  source: string;
}

export interface RemoteState {
  rev: number;
  queueRev: number;
  playback: string;
  track: TrackMeta | null;
  positionMs: number;
  durationMs: number;
  volume: number | null;
  shuffle: boolean;
  repeat: string;
  queueIndex: number;
  queue: TrackMeta[] | null;
  error: string | null;
  capabilities: string[];
}

interface ConnectUpdate {
  devices: ConnectDevice[];
  prompts: PairingPrompt[];
}

export function metaToTrack(meta: TrackMeta): Track {
  return {
    id: meta.id,
    title: meta.title,
    artist: meta.artist,
    artists: (meta.artists ?? []).map((a) => ({ id: a.id ?? '', name: a.name })),
    album: meta.album
      ? { id: meta.album.id ?? '', title: meta.album.title, cover_url: meta.artworkUrl }
      : null,
    duration_secs: Math.round((meta.durationMs ?? 0) / 1000),
    thumbnail_url: meta.artworkUrl,
    provider: meta.source === 'local' ? 'LocalLibrary' : 'YouTubeMusic',
    uri: null,
  };
}

export function trackToMeta(track: Track): TrackMeta {
  return {
    id: track.id,
    title: track.title,
    artist: track.artist,
    artists: (track.artists ?? []).map((a) => ({ name: a.name, id: a.id })),
    album: track.album ? { title: track.album.title, id: track.album.id } : null,
    durationMs: Math.round(track.duration_secs * 1000),
    artworkUrl: track.thumbnail_url,
    source: track.provider === 'LocalLibrary' ? 'local' : 'youtube',
  };
}

const LOCAL = '';

class ConnectStore {
  local = $state<LocalDevice | null>(null);
  devices = $state<ConnectDevice[]>([]);
  prompts = $state<PairingPrompt[]>([]);
  /**
   * The active playback device, Spotify Connect style: null = this PC.
   * It follows whichever device is playing; a paused remote stays active.
   */
  target = $state<string | null>(null);
  /** Latest state of every paired, connected device. */
  remotes = $state<Record<string, { frame: RemoteState; at: number }>>({});
  error = $state<string | null>(null);
  showDevices = $state(false);
  /** A transfer is in flight; the picker shows progress and ignores taps. */
  transferring = $state<string | null>(null);

  remote = $derived<RemoteState | null>(this.target ? this.remotes[this.target]?.frame ?? null : null);
  private remoteAt = $derived(this.target ? this.remotes[this.target]?.at ?? 0 : 0);

  targetName = $derived(
    this.target === null
      ? null
      : this.devices.find((d) => d.id === this.target)?.name ?? 'device',
  );

  /** Paired devices, connected or not, for the Connect picker. */
  paired = $derived(this.devices.filter((d) => d.trusted && d.id));

  remoteQueue = $derived<Track[]>((this.remote?.queue ?? []).map(metaToTrack));

  /** Position extrapolated locally while the remote is playing. */
  remotePositionSecs = $derived.by(() => {
    const frame = this.remote;
    if (!frame) return 0;
    const base = (frame.positionMs ?? 0) / 1000;
    if (frame.playback !== 'playing') return base;
    return base + (this.now - this.remoteAt) / 1000;
  });
  private now = $state(Date.now());

  remoteDurationSecs = $derived((this.remote?.durationMs ?? 0) / 1000);
  remotePlaying = $derived(this.remote?.playback === 'playing');
  remoteTrack = $derived<Track | null>(
    this.remote?.track ? metaToTrack(this.remote.track) : null,
  );
  remoteIndex = $derived(this.remote?.queueIndex ?? -1);
  remoteVolume = $derived(this.remote?.volume ?? 1);

  constructor() {
    void this.init();
  }

  private async init() {
    try {
      this.local = await invoke<LocalDevice>('connect_local');
    } catch (e) {
      console.warn('connect unavailable:', e);
    }
    try {
      this.devices = await invoke<ConnectDevice[]>('connect_devices');
      this.prompts = await invoke<PairingPrompt[]>('connect_prompts');
    } catch (e) {
      console.warn('connect state unavailable:', e);
    }
    await listen<ConnectUpdate>('connect://update', (event) => {
      this.devices = event.payload.devices;
      this.prompts = event.payload.prompts;
      if (this.target && !this.paired.some((d) => d.id === this.target)) {
        this.target = null;
      }
    });
    setInterval(() => void this.pollRemotes(), 500);
  }

  /** Remote position extrapolated to this instant (for transfers). */
  private positionOf(id: string): number {
    const entry = this.remotes[id];
    if (!entry) return 0;
    const base = entry.frame.positionMs ?? 0;
    return entry.frame.playback === 'playing' ? base + (Date.now() - entry.at) : base;
  }

  /** Local-clock time each device (LOCAL = this PC) was seen starting to play. */
  private startedAt: Record<string, number> = {};
  private wasPlaying: Record<string, boolean> = {};

  private async pollRemotes() {
    this.now = Date.now();
    let states: Record<string, RemoteState> = {};
    try {
      states = await invoke<Record<string, RemoteState>>('connect_remote_states');
    } catch {
      return; // Transient; the next poll retries.
    }
    const next: Record<string, { frame: RemoteState; at: number }> = {};
    for (const [id, frame] of Object.entries(states)) {
      const prev = this.remotes[id];
      // Keep the receive time of an unchanged frame so extrapolation is right.
      next[id] = prev && prev.frame.rev === frame.rev ? prev : { frame, at: Date.now() };
      const playing = frame.playback === 'playing';
      if (playing && !this.wasPlaying[id]) this.startedAt[id] = Date.now();
      this.wasPlaying[id] = playing;
    }
    for (const id of Object.keys(this.startedAt)) {
      if (id !== LOCAL && !(id in next)) {
        delete this.startedAt[id];
        delete this.wasPlaying[id];
      }
    }
    this.remotes = next;
    // This PC started playing: it becomes current and other devices stop, so
    // only one device plays at a time.
    if (player.isPlaying && !this.wasPlaying[LOCAL]) {
      this.startedAt[LOCAL] = Date.now();
      if (!this.transferring) {
        for (const [id, r] of Object.entries(next)) {
          if (r.frame.playback === 'playing') {
            void invoke('connect_command', { deviceId: id, action: 'pause', args: {} }).catch(() => {});
          }
        }
      }
    }
    this.wasPlaying[LOCAL] = player.isPlaying;
    if (this.transferring) return;
    // Current = the device that most recently started playing.
    let best: string | null = null;
    let bestAt = -1;
    for (const [id, at] of Object.entries(this.startedAt)) {
      if (at > bestAt && (id === LOCAL || id in next)) {
        best = id;
        bestAt = at;
      }
    }
    this.target = best === null || best === LOCAL ? null : best;
  }

  /**
   * Make `deviceId` (null = this PC) the active device. Whatever is playing
   * moves there and continues from the same position; the old device stops
   * only after the new one confirmed.
   */
  async select(deviceId: string | null) {
    const from = this.target;
    if (deviceId === from || this.transferring) return;
    this.error = null;
    this.transferring = deviceId ?? 'local';
    try {
      if (from === null) {
        if (player.currentTrack && deviceId) {
          await invoke('connect_handoff', {
            deviceId,
            request: {
              transferId: '',
              queue: player.queue.map(trackToMeta),
              index: player.currentIndex ?? 0,
              positionMs: Math.round(player.positionSecs * 1000),
              playing: player.isPlaying,
              shuffle: false,
              repeat: 'off',
            },
          });
          if (player.isPlaying) await player.togglePlay();
        }
      } else {
        const frame = this.remotes[from]?.frame;
        const playing = frame?.playback === 'playing';
        if (frame?.track) {
          const positionMs = Math.round(this.positionOf(from));
          if (deviceId === null) {
            await invoke('connect_pull', { deviceId: from, positionMs });
            await player.refreshQueue();
          } else {
            await invoke('connect_handoff', {
              deviceId,
              request: {
                transferId: '',
                queue: frame.queue ?? [frame.track],
                index: Math.max(0, frame.queueIndex),
                positionMs,
                playing,
                shuffle: frame.shuffle,
                repeat: frame.repeat,
              },
            });
          }
          if (playing) {
            await invoke('connect_command', { deviceId: from, action: 'pause', args: {} });
          }
        }
      }
      this.startedAt[deviceId ?? LOCAL] = Date.now();
      this.target = deviceId;
    } catch (e) {
      this.error = String(e);
    } finally {
      this.transferring = null;
    }
  }

  async pair(target: string): Promise<string | null> {
    try {
      this.error = null;
      return await invoke<string | null>('connect_pair', { target });
    } catch (e) {
      this.error = String(e);
      return null;
    }
  }

  async confirm(promptId: string, accepted: boolean) {
    try {
      await invoke('connect_confirm', { promptId, accepted });
    } catch (e) {
      this.error = String(e);
    }
  }

  async command(action: string, args: Record<string, unknown> = {}) {
    if (!this.target) return;
    try {
      this.error = null;
      await invoke('connect_command', {
        deviceId: this.target,
        action,
        args,
      });
      await this.pollRemotes();
    } catch (e) {
      this.error = String(e);
    }
  }

  async disconnect(deviceId: string) {
    if (this.target === deviceId) this.target = null;
    await invoke('connect_disconnect', { deviceId }).catch((e) => (this.error = String(e)));
  }

  async remove(deviceId: string) {
    if (this.target === deviceId) this.target = null;
    await invoke('connect_unpair', { deviceId });
  }

  /** Remote equivalents of the local player actions used while controlling. */
  remoteToggle() {
    return this.command('toggle');
  }
  remoteNext() {
    return this.command('next');
  }
  remotePrevious() {
    return this.command('previous');
  }
  remoteSeek(secs: number) {
    return this.command('seek', { positionMs: Math.round(secs * 1000) });
  }
  remoteSetVolume(volume: number) {
    return this.command('setVolume', { volume });
  }
  remotePlayQueueIndex(index: number) {
    return this.command('playQueueIndex', {
      index,
      queueRev: this.remote?.queueRev ?? 0,
    });
  }
  remotePlayTrack(track: Track) {
    return this.command('playTrack', { track: trackToMeta(track) });
  }
  remoteAddToQueue(track: Track) {
    return this.command('addToQueue', { track: trackToMeta(track) });
  }
}

export const connect = new ConnectStore();

/**
 * Playback view: while a remote device is the control target this projects
 * its state and routes actions over Connect; otherwise it proxies the local
 * player store. Components read playback exclusively through `view`.
 */
export const view = {
  get currentTrack() {
    return connect.target ? connect.remoteTrack : player.currentTrack;
  },
  get isPlaying() {
    return connect.target ? connect.remotePlaying : player.isPlaying;
  },
  get positionSecs() {
    return connect.target ? connect.remotePositionSecs : player.positionSecs;
  },
  get durationSecs() {
    return connect.target ? connect.remoteDurationSecs : player.durationSecs;
  },
  get volume() {
    return connect.target ? connect.remoteVolume : player.volume;
  },
  get queue() {
    return connect.target ? connect.remoteQueue : player.queue;
  },
  get currentIndex() {
    return connect.target ? connect.remoteIndex : player.currentIndex;
  },
  get controlling() {
    return connect.target !== null;
  },
  get canPlay() {
    return connect.target ? connect.remoteQueue.length > 0 || !!connect.remoteTrack : player.canPlay;
  },
  get canPrevious() {
    return connect.target ? (connect.remoteIndex ?? 0) > 0 : player.canPrevious;
  },
  get canNext() {
    return connect.target
      ? connect.remoteIndex + 1 < connect.remoteQueue.length
      : player.canNext;
  },
  get canSeek() {
    return connect.target
      ? connect.remoteDurationSecs > 0 && (connect.remote?.capabilities ?? []).includes('seek')
      : player.canSeek;
  },
  get isBusy() {
    return player.isBusy;
  },
  togglePlay() {
    return connect.target ? connect.remoteToggle() : player.togglePlay();
  },
  next() {
    return connect.target ? connect.remoteNext() : player.next();
  },
  previous() {
    return connect.target ? connect.remotePrevious() : player.previous();
  },
  seek(secs: number) {
    return connect.target ? connect.remoteSeek(secs) : player.seek(secs);
  },
  setVolume(vol: number) {
    return connect.target ? connect.remoteSetVolume(vol) : player.setVolume(vol);
  },
  playQueueItem(index: number) {
    return connect.target ? connect.remotePlayQueueIndex(index) : player.playQueueItem(index);
  },
  playTrack(track: Track) {
    return connect.target ? connect.remotePlayTrack(track) : player.playTrack(track);
  },
  addToQueue(track: Track) {
    return connect.target ? connect.remoteAddToQueue(track) : player.addToQueue(track);
  },
};
