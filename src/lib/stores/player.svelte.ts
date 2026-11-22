import { invoke } from '@tauri-apps/api/core';
import type { PlaybackProgress, QueueState, Track } from '$lib/types';

class PlayerStore {
  currentTrack = $state<Track | null>(null);
  isPlaying = $state<boolean>(false);
  isPaused = $state<boolean>(false);
  isBusy = $state(false);
  error = $state<string | null>(null);
  positionSecs = $state<number>(0);
  durationSecs = $state<number>(0);
  volume = $state<number>(1.0);
  queue = $state<Track[]>([]);
  currentIndex = $state<number | null>(null);
  canPlay = $derived(!this.isBusy && this.queue.length > 0);
  canPrevious = $derived(!this.isBusy && (this.currentIndex ?? 0) > 0);
  canNext = $derived(!this.isBusy && (this.currentIndex ?? -1) + 1 < this.queue.length);
  canSeek = $derived(!this.isBusy && this.durationSecs > 0 && (this.isPlaying || this.isPaused));
  favorites = $state<Track[]>([]);
  history = $state<Track[]>([]);
  showNowPlayingModal = $state<boolean>(false);

  private polling = false;
  private playbackVersion = 0;

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

  private async syncPlayback(version: number) {
    const progress = await invoke<PlaybackProgress>('get_playback_state');
    const queue = await invoke<QueueState>('get_queue');
    if (version !== this.playbackVersion) return;
    const changedTrack = progress.current_track?.id !== this.currentTrack?.id || queue.current_index !== this.currentIndex;
    this.positionSecs = progress.position_secs;
    this.durationSecs = progress.duration_secs;
    this.currentTrack = progress.current_track;
    this.isPlaying = progress.state === 'Playing';
    this.isPaused = progress.state === 'Paused';
    this.queue = queue.items;
    this.currentIndex = queue.current_index;
    if (changedTrack) void this.refreshHistory();
  }

  private startPolling() {
    setInterval(async () => {
      if (this.polling || this.isBusy) return;
      this.polling = true;
      const version = this.playbackVersion;
      try {
        await this.syncPlayback(version);
      } catch (e) {
        if (version === this.playbackVersion) this.error = String(e);
      } finally {
        this.polling = false;
      }
    }, 500);
  }

  private async runPlayback(command: string, args?: Record<string, unknown>) {
    if (this.isBusy) return;
    this.isBusy = true;
    const version = ++this.playbackVersion;
    this.error = null;
    try {
      await invoke(command, args);
      await this.syncPlayback(version);
    } catch (e) {
      this.error = String(e);
    } finally {
      this.isBusy = false;
    }
  }

  playTrack(track: Track) {
    return this.runPlayback('play_track', { track });
  }

  playQueueItem(index: number) {
    return this.runPlayback('play_queue_item', { index });
  }

  openAudioFile() {
    return this.runPlayback('open_audio_file');
  }

  async addToQueue(track: Track) {
    try {
      await invoke('add_to_queue', { track });
      await this.refreshQueue();
    } catch (e) {
      this.error = String(e);
    }
  }

  togglePlay() {
    return this.runPlayback('toggle_play_pause');
  }

  next() {
    return this.runPlayback('next_track');
  }

  previous() {
    return this.runPlayback('previous_track');
  }

  seek(position: number) {
    return this.runPlayback('seek', { positionSecs: position });
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
