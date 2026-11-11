<script lang="ts">
  import { player } from '$lib/stores/player.svelte';
  import { X, Play, Pause, SkipBack, SkipForward, Heart } from 'lucide-svelte';

  let track = $derived(player.currentTrack);
  let isPlaying = $derived(player.isPlaying);
  let isFav = $derived(track ? player.isFavorite(track.id) : false);

  function handleSeek(e: Event) {
    const target = e.target as HTMLInputElement;
    player.seek(parseFloat(target.value));
  }
</script>

{#if player.showNowPlayingModal && track}
  <div class="fixed inset-0 z-50 bg-[#0e0f12] flex flex-col justify-between p-8 md:p-12 animate-fade-in select-none">
    <!-- Top Bar -->
    <div class="flex items-center justify-between w-full max-w-4xl mx-auto">
      <div class="text-xs uppercase tracking-wider font-mono text-text-muted">Now Playing</div>
      <button
        onclick={() => (player.showNowPlayingModal = false)}
        class="text-text-secondary hover:text-text-primary p-2 transition-colors rounded-full hover:bg-surface-hover"
        aria-label="Close"
      >
        <X size={20} />
      </button>
    </div>

    <!-- Center Album Art & Info -->
    <div class="flex flex-col md:flex-row items-center justify-center gap-10 max-w-4xl w-full mx-auto my-auto">
      <div class="w-72 h-72 md:w-96 md:h-96 rounded-lg bg-surface-elevated overflow-hidden shadow-2xl flex-shrink-0 border border-surface-border">
        {#if track.thumbnail_url}
          <img src={track.thumbnail_url} alt={track.title} class="w-full h-full object-cover" />
        {:else}
          <div class="w-full h-full flex items-center justify-center text-4xl text-text-muted font-mono">
            ♪
          </div>
        {/if}
      </div>

      <div class="flex flex-col text-left w-full md:max-w-md">
        <div class="flex items-start justify-between gap-4">
          <div>
            <h1 class="text-2xl md:text-3xl font-semibold text-text-primary leading-tight">{track.title}</h1>
            <p class="text-base md:text-lg text-text-secondary mt-1">{track.artist}</p>
            {#if track.album}
              <p class="text-xs text-text-muted font-mono mt-2">{track.album.title}</p>
            {/if}
          </div>
          <button
            onclick={() => track && player.toggleFavorite(track)}
            class="text-text-secondary hover:text-text-primary p-2 transition-colors"
            aria-label="Favorite"
          >
            <Heart size={24} fill={isFav ? '#EDEDEF' : 'none'} color={isFav ? '#EDEDEF' : 'currentColor'} />
          </button>
        </div>

        <!-- Timeline -->
        <div class="w-full mt-8">
          <input
            type="range"
            min="0"
            max={player.durationSecs || 100}
            value={player.positionSecs}
            oninput={handleSeek}
            class="w-full h-1.5 bg-surface-border rounded-lg appearance-none cursor-pointer accent-accent-highlight"
          />
          <div class="flex justify-between text-xs font-mono text-text-muted mt-2">
            <span>{player.formatTime(player.positionSecs)}</span>
            <span>{player.formatTime(player.durationSecs)}</span>
          </div>
        </div>

        <!-- Big Playback Controls -->
        <div class="flex items-center justify-center gap-8 mt-6">
          <button
            onclick={() => player.previous()}
            class="text-text-secondary hover:text-text-primary transition p-2"
            aria-label="Previous"
          >
            <SkipBack size={26} />
          </button>
          <button
            onclick={() => player.togglePlay()}
            class="w-16 h-16 rounded-full bg-text-primary text-surface-base flex items-center justify-center hover:opacity-90 active:scale-95 transition"
            aria-label={isPlaying ? 'Pause' : 'Play'}
          >
            {#if isPlaying}
              <Pause size={28} fill="currentColor" />
            {:else}
              <Play size={28} fill="currentColor" class="translate-x-0.5" />
            {/if}
          </button>
          <button
            onclick={() => player.next()}
            class="text-text-secondary hover:text-text-primary transition p-2"
            aria-label="Next"
          >
            <SkipForward size={26} />
          </button>
        </div>
      </div>
    </div>

    <!-- Bottom Indicator -->
    <div class="text-center text-xs text-text-muted font-mono w-full max-w-4xl mx-auto">
      Source: {track.provider} &bull; Native Playback Engine
    </div>
  </div>
{/if}
