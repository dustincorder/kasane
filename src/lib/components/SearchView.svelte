<script lang="ts">
  import { player } from '$lib/stores/player.svelte';
  import { invoke } from '@tauri-apps/api/core';
  import type { Track } from '$lib/types';
  import { Search as SearchIcon, Play, Plus, Loader2 } from 'lucide-svelte';

  let query = $state('');
  let useMock = $state(false);
  let loading = $state(false);
  let results = $state<Track[]>([]);

  async function handleSearch() {
    if (!query.trim()) return;
    loading = true;
    try {
      results = await invoke<Track[]>('search_tracks', {
        query: query.trim(),
        useMock: useMock,
      });
    } catch (e) {
      console.error('Search failed:', e);
    } finally {
      loading = false;
    }
  }

  function handleKeydown(e: KeyboardEvent) {
    if (e.key === 'Enter') {
      handleSearch();
    }
  }
</script>

<div class="p-6 max-w-5xl mx-auto space-y-6">
  <!-- Search Bar -->
  <div class="flex items-center gap-3">
    <div class="relative flex-1">
      <SearchIcon size={18} class="absolute left-3.5 top-1/2 -translate-y-1/2 text-text-muted" />
      <input
        type="text"
        bind:value={query}
        onkeydown={handleKeydown}
        placeholder="Search songs, artists, or genres on YouTube Music..."
        class="w-full bg-surface-elevated border border-surface-border rounded-lg pl-10 pr-4 py-2.5 text-sm text-text-primary placeholder-text-muted focus:outline-none focus:border-accent-active transition"
      />
    </div>

    <button
      onclick={handleSearch}
      class="bg-accent-subtle hover:bg-surface-hover text-text-primary text-sm font-medium px-5 py-2.5 rounded-lg border border-surface-border transition flex items-center gap-2"
    >
      {#if loading}
        <Loader2 size={16} class="animate-spin" />
      {:else}
        Search
      {/if}
    </button>
  </div>

  <!-- Source toggle -->
  <div class="flex items-center gap-2 text-xs text-text-secondary">
    <label class="flex items-center gap-1.5 cursor-pointer">
      <input type="checkbox" bind:checked={useMock} class="rounded border-surface-border bg-surface-elevated text-accent-active" />
      <span>Offline Mock Catalog Mode</span>
    </label>
  </div>

  <!-- Search Results Table -->
  <div class="space-y-2">
    <h2 class="text-xs uppercase font-mono tracking-wider text-text-muted">
      {#if results.length > 0}
        Found {results.length} Tracks
      {:else if !loading}
        No search results yet
      {/if}
    </h2>

    <div class="divide-y divide-surface-border/50 border border-surface-border rounded-lg overflow-hidden bg-surface-elevated">
      {#each results as track}
        <div class="flex items-center justify-between p-3 hover:bg-surface-hover transition group">
          <div class="flex items-center gap-3 min-w-0">
            <div class="w-10 h-10 rounded bg-surface-base flex-shrink-0 overflow-hidden relative">
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

          <div class="flex items-center gap-2 flex-shrink-0">
            <span class="text-xs font-mono text-text-muted mr-3">{player.formatTime(track.duration_secs)}</span>
            <button
              onclick={() => player.addToQueue(track)}
              class="p-2 text-text-secondary hover:text-text-primary rounded hover:bg-surface-border transition"
              title="Add to queue"
            >
              <Plus size={16} />
            </button>
            <button
              onclick={() => player.playTrack(track)}
              class="p-2 text-surface-base bg-text-primary rounded-full hover:opacity-90 active:scale-95 transition"
              title="Play now"
            >
              <Play size={14} fill="currentColor" class="translate-x-0.5" />
            </button>
          </div>
        </div>
      {/each}
    </div>
  </div>
</div>
