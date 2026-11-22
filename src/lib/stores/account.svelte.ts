import { invoke } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import type { Track } from '$lib/types';
import { player } from './player.svelte';

export interface AccountStatus {
  signed_in: boolean;
  name: string | null;
  avatar: string | null;
  channel_id: string | null;
  persistence: 'keyring' | 'memory';
}

export interface LibraryPlaylist {
  id: string;
  name: string;
  thumbnail_url: string | null;
  track_count: number;
}

export interface StorageInfo {
  app_data_dir: string;
  database_path: string;
  database_bytes: number;
  connect_bytes: number;
}

export interface ConnectStatus {
  enabled: boolean;
  device_name: string;
  device_id: string;
  port: number;
}

class AccountStore {
  status = $state<AccountStatus>({
    signed_in: false,
    name: null,
    avatar: null,
    channel_id: null,
    persistence: 'keyring',
  });
  signingIn = $state(false);
  error = $state<string | null>(null);

  liked = $state<Track[]>([]);
  playlists = $state<LibraryPlaylist[]>([]);
  libraryLoaded = $state(false);
  libraryLoading = $state(false);
  /** Playlist id currently open in Library, or null. */
  openPlaylistId = $state<string | null>(null);
  openPlaylistTracks = $state<Track[]>([]);

  connectEnabled = $state(true);
  connectName = $state('');

  storage = $state<StorageInfo | null>(null);

  get signedIn() {
    return this.status.signed_in;
  }

  async init() {
    await Promise.all([this.refreshStatus(), this.refreshConnect(), this.refreshStorage()]);
    void listen<AccountStatus>('account://update', (event) => {
      this.status = event.payload;
      this.signingIn = false;
      if (event.payload.signed_in) void this.loadLibrary();
      else {
        this.liked = [];
        this.playlists = [];
        this.libraryLoaded = false;
        this.openPlaylistId = null;
      }
    });
  }

  async refreshStatus() {
    try {
      this.status = await invoke<AccountStatus>('account_status');
      if (this.status.signed_in && !this.libraryLoaded) void this.loadLibrary();
    } catch (e) {
      console.warn('account_status failed:', e);
    }
  }

  async refreshConnect() {
    try {
      const s = await invoke<ConnectStatus>('connect_status');
      this.connectEnabled = s.enabled;
      this.connectName = s.device_name;
    } catch (e) {
      console.warn('connect_status failed:', e);
    }
  }

  async refreshStorage() {
    try {
      this.storage = await invoke<StorageInfo>('storage_info');
    } catch (e) {
      console.warn('storage_info failed:', e);
    }
  }

  /** Opens the Google sign-in window; the session lands via account://update. */
  async signIn() {
    this.signingIn = true;
    this.error = null;
    try {
      await invoke('account_sign_in');
    } catch (e) {
      this.signingIn = false;
      this.error = String(e);
    }
  }

  async signOut() {
    try {
      await invoke('account_sign_out');
    } catch (e) {
      this.error = String(e);
    }
  }

  async setConnectEnabled(enabled: boolean) {
    try {
      const s = await invoke<ConnectStatus>('connect_set_enabled', { enabled });
      this.connectEnabled = s.enabled;
      this.connectName = s.device_name;
    } catch (e) {
      this.error = String(e);
      this.connectEnabled = !enabled; // revert the toggle
    }
  }

  async loadLibrary() {
    if (this.libraryLoading) return;
    this.libraryLoading = true;
    try {
      const [liked, playlists] = await Promise.all([
        invoke<Track[]>('account_liked_music'),
        invoke<LibraryPlaylist[]>('account_playlists'),
      ]);
      this.liked = liked;
      this.playlists = playlists;
      this.libraryLoaded = true;
    } catch (e) {
      console.warn('account library failed:', e);
    } finally {
      this.libraryLoading = false;
    }
  }

  async openPlaylist(id: string) {
    this.openPlaylistId = id;
    try {
      this.openPlaylistTracks = await invoke<Track[]>('account_playlist_tracks', {
        playlistId: id,
      });
    } catch (e) {
      this.error = String(e);
      this.openPlaylistId = null;
    }
  }

  /** Plays a track list in order: first now, the rest appended to the queue. */
  async playInOrder(tracks: Track[]) {
    const [first, ...rest] = tracks;
    if (!first) return;
    await player.playTrack(first);
    for (const track of rest) {
      await player.addToQueue(track);
    }
  }

  formatBytes(n: number): string {
    if (n < 1024) return `${n} B`;
    if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
    if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`;
    return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`;
  }
}

export const account = new AccountStore();
