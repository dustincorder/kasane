<script lang="ts">
  import { player } from '$lib/stores/player.svelte';
  import { Play, ListMusic } from 'lucide-svelte';

  let queue = $derived(player.queue);
  let currentIndex = $derived(player.currentIndex);
</script>

<div class="p-6 max-w-4xl mx-auto space-y-6 select-none">
  <div class="flex items-center justify-between border-b border-surface-border pb-4">
    <div class="flex items-center gap-2">
      <ListMusic size={20} class="text-text-primary" />
      <h1 class="text-lg font-semibold text-text-primary">Play Queue</h1>
      <span class="text-xs font-mono text-text-muted">({queue.length} items)</span>
    </div>
  </div>

  {#if queue.length === 0}
    <div class="p-12 border border-dashed border-surface-border rounded-lg text-center text-text-muted text-sm">
      Queue is currently empty. Use the '+' button in search or library to queue tracks.
    </div>
  {:else}
    <div class="divide-y divide-surface-border/50 border border-surface-border rounded-lg overflow-hidden bg-surface-elevated">
      {#each queue as track, index}
        <div
          class="flex items-center justify-between p-3 transition group {index === currentIndex ? 'bg-surface-hover/80 border-l-2 border-accent-active' : 'hover:bg-surface-hover'}"
        >
          <div class="flex items-center gap-3 min-w-0">
            <span class="text-xs font-mono w-5 text-center text-text-muted">
              {index + 1}
            </span>
            <div class="w-9 h-9 rounded bg-surface-base flex-shrink-0 overflow-hidden">
              {#if track.thumbnail_url}
                <img src={track.thumbnail_url} alt={track.title} class="w-full h-full object-cover" />
              {:else}
                <div class="w-full h-full flex items-center justify-center text-xs font-mono text-text-muted">♪</div>
              {/if}
            </div>
            <div class="truncate">
              <div class="text-sm font-medium {index === currentIndex ? 'text-accent-highlight' : 'text-text-primary'} truncate">
                {track.title}
              </div>
              <div class="text-xs text-text-secondary truncate">{track.artist}</div>
            </div>
          </div>

          <div class="flex items-center gap-3">
            <span class="text-xs font-mono text-text-muted">{player.formatTime(track.duration_secs)}</span>
            <button
              onclick={() => player.playTrack(track)}
              class="p-1.5 text-text-secondary hover:text-text-primary rounded hover:bg-surface-border transition"
              title="Play now"
            >
              <Play size={14} />
            </button>
          </div>
        </div>
      {/each}
    </div>
  {/if}
</div>
