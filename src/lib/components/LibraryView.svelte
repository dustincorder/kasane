<script lang="ts">
  import { player } from '$lib/stores/player.svelte';
  import { Play, Heart, Clock } from 'lucide-svelte';

  let favorites = $derived(player.favorites);
  let history = $derived(player.history);
</script>

<div class="p-6 max-w-5xl mx-auto space-y-8 select-none">
  <!-- Favorites Section -->
  <section class="space-y-3">
    <div class="flex items-center gap-2 text-text-primary font-semibold text-lg">
      <Heart size={18} class="text-text-primary" />
      <span>Favorites ({favorites.length})</span>
    </div>

    {#if favorites.length === 0}
      <div class="p-8 border border-dashed border-surface-border rounded-lg text-center text-text-muted text-sm">
        No favorite tracks saved yet. Heart a track during playback or search to keep it here.
      </div>
    {:else}
      <div class="grid grid-cols-1 md:grid-cols-2 gap-3">
        {#each favorites as track}
          <div class="flex items-center justify-between p-3 bg-surface-elevated border border-surface-border rounded-lg hover:bg-surface-hover transition group">
            <div class="flex items-center gap-3 min-w-0">
              <div class="w-10 h-10 rounded bg-surface-base flex-shrink-0 overflow-hidden">
                {#if track.thumbnail_url}
                  <img src={track.thumbnail_url} alt={track.title} class="w-full h-full object-cover" />
                {:else}
                  <div class="w-full h-full flex items-center justify-center text-xs font-mono text-text-muted">♪</div>
                {/if}
              </div>
              <div class="truncate">
                <div class="text-sm font-medium text-text-primary truncate">{track.title}</div>
                <div class="text-xs text-text-secondary truncate">{track.artist}</div>
              </div>
            </div>

            <div class="flex items-center gap-2">
              <button
                onclick={() => player.playTrack(track)}
                class="p-2 text-surface-base bg-text-primary rounded-full hover:opacity-90 active:scale-95 transition"
                title="Play track"
              >
                <Play size={14} fill="currentColor" class="translate-x-0.5" />
              </button>
            </div>
          </div>
        {/each}
      </div>
    {/if}
  </section>

  <!-- Listening History Section -->
  <section class="space-y-3">
    <div class="flex items-center gap-2 text-text-primary font-semibold text-lg">
      <Clock size={18} class="text-text-primary" />
      <span>Recently Played</span>
    </div>

    {#if history.length === 0}
      <div class="p-8 border border-dashed border-surface-border rounded-lg text-center text-text-muted text-sm">
        History is empty. Songs you play will appear here automatically.
      </div>
    {:else}
      <div class="divide-y divide-surface-border/50 border border-surface-border rounded-lg overflow-hidden bg-surface-elevated">
        {#each history as track}
          <div class="flex items-center justify-between p-3 hover:bg-surface-hover transition group">
            <div class="flex items-center gap-3 min-w-0">
              <div class="w-9 h-9 rounded bg-surface-base flex-shrink-0 overflow-hidden">
                {#if track.thumbnail_url}
                  <img src={track.thumbnail_url} alt={track.title} class="w-full h-full object-cover" />
                {:else}
                  <div class="w-full h-full flex items-center justify-center text-xs font-mono text-text-muted">♪</div>
                {/if}
              </div>
              <div class="truncate">
                <div class="text-sm font-medium text-text-primary truncate">{track.title}</div>
                <div class="text-xs text-text-secondary truncate">{track.artist}</div>
              </div>
            </div>

            <div class="flex items-center gap-3">
              <span class="text-xs font-mono text-text-muted">{player.formatTime(track.duration_secs)}</span>
              <button
                onclick={() => player.playTrack(track)}
                class="p-1.5 text-text-secondary hover:text-text-primary rounded hover:bg-surface-border transition"
                title="Play again"
              >
                <Play size={14} />
              </button>
            </div>
          </div>
        {/each}
      </div>
    {/if}
  </section>
</div>
