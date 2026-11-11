<script lang="ts">
  import { player } from '$lib/stores/player.svelte';
  import { Play, Pause, SkipBack, SkipForward, Heart, Volume2, Maximize2 } from 'lucide-svelte';

  let track = $derived(player.currentTrack);
  let isPlaying = $derived(player.isPlaying);
  let isFav = $derived(track ? player.isFavorite(track.id) : false);

  function handleSeek(e: Event) {
    const target = e.target as HTMLInputElement;
    player.seek(parseFloat(target.value));
  }

  function handleVolume(e: Event) {
    const target = e.target as HTMLInputElement;
    player.setVolume(parseFloat(target.value));
  }
</script>

<footer class="h-20 bg-surface-elevated border-t border-surface-border px-4 flex items-center justify-between z-30 select-none">
  <!-- Track Info -->
  <div class="flex items-center gap-3 w-1/4 min-w-[200px]">
    {#if track}
      <div class="w-12 h-12 rounded bg-surface-hover flex-shrink-0 overflow-hidden relative group">
        {#if track.thumbnail_url}
          <img src={track.thumbnail_url} alt={track.title} class="w-full h-full object-cover" />
        {:else}
          <div class="w-full h-full bg-surface-border flex items-center justify-center text-text-muted text-xs font-mono">
            ♪
          </div>
        {/if}
      </div>
      <div class="truncate">
        <div class="text-sm font-medium text-text-primary truncate">{track.title}</div>
        <div class="text-xs text-text-secondary truncate">{track.artist}</div>
      </div>
      <button
        onclick={() => track && player.toggleFavorite(track)}
        class="ml-2 text-text-muted hover:text-text-primary transition-colors p-1"
        aria-label="Favorite"
      >
        <Heart size={16} fill={isFav ? '#EDEDEF' : 'none'} color={isFav ? '#EDEDEF' : 'currentColor'} />
      </button>
    {:else}
      <div class="text-xs text-text-muted">No track selected</div>
    {/if}
  </div>

  <!-- Player Controls & Timeline -->
  <div class="flex flex-col items-center gap-1.5 w-2/4 max-w-xl">
    <div class="flex items-center gap-5">
      <button
        onclick={() => player.previous()}
        class="text-text-secondary hover:text-text-primary transition-colors p-1 disabled:opacity-30"
        aria-label="Previous"
        disabled={!track}
      >
        <SkipBack size={18} />
      </button>

      <button
        onclick={() => player.togglePlay()}
        class="w-9 h-9 rounded-full bg-text-primary text-surface-base flex items-center justify-center hover:opacity-90 active:scale-95 transition disabled:opacity-30"
        aria-label={isPlaying ? 'Pause' : 'Play'}
        disabled={!track}
      >
        {#if isPlaying}
          <Pause size={18} fill="currentColor" />
        {:else}
          <Play size={18} fill="currentColor" class="translate-x-0.5" />
        {/if}
      </button>

      <button
        onclick={() => player.next()}
        class="text-text-secondary hover:text-text-primary transition-colors p-1 disabled:opacity-30"
        aria-label="Next"
        disabled={!track}
      >
        <SkipForward size={18} />
      </button>
    </div>

    <!-- Scrubber -->
    <div class="w-full flex items-center gap-2 text-[11px] font-mono text-text-muted">
      <span>{player.formatTime(player.positionSecs)}</span>
      <input
        type="range"
        min="0"
        max={player.durationSecs || 100}
        value={player.positionSecs}
        oninput={handleSeek}
        class="w-full h-1 bg-surface-border rounded-lg appearance-none cursor-pointer accent-accent-highlight"
        disabled={!track}
      />
      <span>{player.formatTime(player.durationSecs)}</span>
    </div>
  </div>

  <!-- Volume & Modal Expand -->
  <div class="flex items-center justify-end gap-3 w-1/4 min-w-[200px]">
    <Volume2 size={16} class="text-text-muted flex-shrink-0" />
    <input
      type="range"
      min="0"
      max="1"
      step="0.01"
      value={player.volume}
      oninput={handleVolume}
      class="w-20 h-1 bg-surface-border rounded-lg appearance-none cursor-pointer accent-accent-highlight"
    />
    <button
      onclick={() => (player.showNowPlayingModal = true)}
      class="text-text-muted hover:text-text-primary transition-colors ml-2 p-1"
      aria-label="Expand Now Playing"
      disabled={!track}
    >
      <Maximize2 size={16} />
    </button>
  </div>
</footer>
