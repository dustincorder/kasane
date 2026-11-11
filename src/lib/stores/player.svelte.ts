import { invoke } from '@tauri-apps/api/core';
import type { PlaybackProgress, QueueState, Track } from '$lib/types';

class PlayerStore {
  currentTrack = $state<Track | null>(null);
  isPlaying = $state<boolean>(false);
  isBuffering = $state<boolean>(false);
  positionSecs = $state<number>(0);
  durationSecs = $state<number>(0);
  volume = $state<number>(1.0);
  queue = $state<Track[]>([]);
  currentIndex = $state<number | null>(null);
  favorites = $state<Track[]>([]);
  history = $state<Track[]>([]);
  showNowPlayingModal = $state<boolean>(false);

  private pollInterval: any = null;

  constructor() {
    this.startPolling();
    this.refreshFavorites();
    this.refreshHistory();
  }

  async refreshFavorites() {
    try {
      this.favorites = await invoke<Track[]>('get_favorites');
    } catch (e) {
      console.warn('Failed to load favorites:', e);
    }
  }

  async refreshHistory() {
    try {
      this.history = await invoke<Track[]>('get_history', { limit: 30 });
    } catch (e) {
      console.warn('Failed to load history:', e);
    }
  }

  async refreshQueue() {
    try {
      const q = await invoke<QueueState>('get_queue');
      this.queue = q.items;
      this.currentIndex = q.current_index;
    } catch (e) {
      console.warn('Failed to load queue:', e);
    }
  }

  private startPolling() {
    this.pollInterval = setInterval(async () => {
      try {
        const progress = await invoke<PlaybackProgress>('get_playback_state');
        this.positionSecs = progress.position_secs;
        this.durationSecs = progress.duration_secs;
        this.currentTrack = progress.current_track;
        this.isPlaying = progress.state === 'Playing';
        this.isBuffering = progress.state === 'Buffering';
      } catch (e) {
        // Suppress polling log spam in dev / when tauri isn't responding
      }
    }, 500);
  }

  async playTrack(track: Track) {
    try {
      await invoke('play_track', { track });
      this.currentTrack = track;
      this.isPlaying = true;
      await this.refreshQueue();
      await this.refreshHistory();
    } catch (e) {
      console.error('Play track failed:', e);
    }
  }

  async addToQueue(track: Track) {
    try {
      await invoke('add_to_queue', { track });
      await this.refreshQueue();
    } catch (e) {
      console.error('Add to queue failed:', e);
    }
  }

  async togglePlay() {
    try {
      await invoke('toggle_play_pause');
      this.isPlaying = !this.isPlaying;
    } catch (e) {
      console.error('Toggle play failed:', e);
    }
  }

  async next() {
    try {
      await invoke('next_track');
      await this.refreshQueue();
    } catch (e) {
      console.error('Next failed:', e);
    }
  }

  async previous() {
    try {
      await invoke('previous_track');
      await this.refreshQueue();
    } catch (e) {
      console.error('Previous failed:', e);
    }
  }

  async seek(position: number) {
    try {
      this.positionSecs = position;
      await invoke('seek', { positionSecs: position });
    } catch (e) {
      console.error('Seek failed:', e);
    }
  }

  async setVolume(vol: number) {
    try {
      this.volume = vol;
      await invoke('set_volume', { volume: vol });
    } catch (e) {
      console.error('Set volume failed:', e);
    }
  }

  async toggleFavorite(track: Track) {
    try {
      await invoke('toggle_favorite', { track });
      await this.refreshFavorites();
    } catch (e) {
      console.error('Toggle favorite failed:', e);
    }
  }

  isFavorite(trackId: string): boolean {
    return this.favorites.some((t) => t.id === trackId);
  }

  formatTime(secs: number): string {
    if (!secs || isNaN(secs) || secs < 0) return '0:00';
    const m = Math.floor(secs / 60);
    const s = Math.floor(secs % 60);
    return `${m}:${s < 10 ? '0' : ''}${s}`;
  }
}

export const player = new PlayerStore();
