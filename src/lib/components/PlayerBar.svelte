<script lang="ts">
  import { player } from '$lib/stores/player.svelte';
  import { connect, view } from '$lib/stores/connect.svelte';
  import { Play, Pause, SkipBack, SkipForward, Heart, Volume2, Maximize2, MonitorSmartphone } from 'lucide-svelte';

  let track = $derived(view.currentTrack);
  let isPlaying = $derived(view.isPlaying);
  let isFav = $derived(track && !view.controlling ? player.isFavorite(track.id) : false);

  function handleSeek(e: Event) {
    const target = e.target as HTMLInputElement;
    view.seek(parseFloat(target.value));
  }

  function handleVolume(e: Event) {
    const target = e.target as HTMLInputElement;
    view.setVolume(parseFloat(target.value));
  }
</script>

<footer class="h-20 bg-surface-elevated border-t border-surface-border px-4 flex items-center justify-between z-30 select-none">
  <!-- Track Info -->
  <div class="flex items-center gap-2 w-1/4 min-w-0">
    {#if track}
      <div class="hidden sm:block w-12 h-12 rounded bg-surface-hover flex-shrink-0 overflow-hidden relative group">
        {#if track.thumbnail_url}
          <img src={track.thumbnail_url} alt={track.title} class="w-full h-full object-cover" />
        {:else}
          <div class="w-full h-full bg-surface-border flex items-center justify-center text-text-muted text-xs">
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
        <Heart size={16} fill={isFav ? '#EF5C7D' : 'none'} color={isFav ? '#EF5C7D' : 'currentColor'} />
      </button>
    {:else}
      <div class="text-xs text-text-muted">No track selected</div>
    {/if}
  </div>

  <!-- Player Controls & Timeline -->
  <div class="flex flex-col items-center gap-1.5 w-2/4 max-w-xl">
    <div class="flex items-center gap-5">
      <button
        onclick={() => view.previous()}
        class="text-text-secondary hover:text-text-primary transition-colors p-1 disabled:opacity-30"
        aria-label="Previous"
        disabled={!view.canPrevious}
      >
        <SkipBack size={18} />
      </button>

      <button
        onclick={() => view.togglePlay()}
        class="w-9 h-9 rounded-full bg-accent-active text-white flex items-center justify-center hover:opacity-90 active:scale-95 transition disabled:opacity-30"
        aria-label={isPlaying ? 'Pause' : 'Play'}
        disabled={!view.canPlay}
      >
        {#if isPlaying}
          <Pause size={18} fill="currentColor" />
        {:else}
          <Play size={18} fill="currentColor" class="translate-x-0.5" />
        {/if}
      </button>

      <button
        onclick={() => view.next()}
        class="text-text-secondary hover:text-text-primary transition-colors p-1 disabled:opacity-30"
        aria-label="Next"
        disabled={!view.canNext}
      >
        <SkipForward size={18} />
      </button>
    </div>

    <!-- Scrubber -->
    <div class="w-full flex items-center gap-2 text-[11px] tabular-nums text-text-muted">
      <span>{player.formatTime(view.positionSecs)}</span>
      <input
        type="range"
        min="0"
        max={view.durationSecs || 100}
        value={view.positionSecs}
        onchange={handleSeek}
        aria-label="Seek"
        class="w-full h-1 bg-surface-border rounded-lg appearance-none cursor-pointer accent-accent-highlight"
        disabled={!view.canSeek}
      />
      <span>{player.formatTime(view.durationSecs)}</span>
    </div>
  </div>

  <!-- Volume & Modal Expand -->
  <div class="flex items-center justify-end gap-2 w-1/4 min-w-0">
    {#if view.controlling}
      <button
        onclick={() => connect.stopControlling()}
        class="hidden sm:flex items-center gap-1.5 text-[11px] text-accent-highlight border border-accent-highlight/40 rounded px-2 py-1 hover:bg-surface-hover transition flex-shrink-0"
        title="Return control to this PC"
      >
        <MonitorSmartphone size={12} />
        Controlling {connect.targetName}
      </button>
    {:else}
      <button
        onclick={() => (connect.showDevices = !connect.showDevices)}
        class="text-text-muted hover:text-text-primary transition-colors p-1 flex-shrink-0"
        aria-label="Devices"
      >
        <MonitorSmartphone size={16} />
      </button>
    {/if}
    <Volume2 size={16} class="hidden sm:block text-text-muted flex-shrink-0" />
    <input
      type="range"
      min="0"
      max="1"
      step="0.01"
      value={view.volume}
      oninput={handleVolume}
      aria-label="Volume"
      class="w-20 min-w-0 h-1 bg-surface-border rounded-lg appearance-none cursor-pointer accent-accent-highlight"
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
