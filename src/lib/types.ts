export type ProviderType = 'YouTubeMusic' | 'LocalLibrary' | 'Mock';

export interface Artist {
  id: string;
  name: string;
}

export interface Album {
  id: string;
  title: string;
  cover_url: string | null;
}

export interface Track {
  id: string;
  title: string;
  artist: string;
  artists: Artist[];
  album: Album | null;
  duration_secs: number;
  thumbnail_url: string | null;
  provider: ProviderType;
  uri: string | null;
}

export type PlaybackState = 'Stopped' | 'Playing' | 'Paused' | 'Buffering' | { Error: string };

export type RepeatMode = 'Off' | 'All' | 'One';

export interface QueueState {
  items: Track[];
  current_index: number | null;
  shuffle: boolean;
  repeat_mode: RepeatMode;
}

export interface PlaybackProgress {
  position_secs: number;
  duration_secs: number;
  state: PlaybackState;
  current_track: Track | null;
}
