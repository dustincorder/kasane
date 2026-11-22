<script lang="ts">
  import PlayerBar from '$lib/components/PlayerBar.svelte';
  import NowPlayingView from '$lib/components/NowPlayingView.svelte';
  import SearchView from '$lib/components/SearchView.svelte';
  import LibraryView from '$lib/components/LibraryView.svelte';
  import QueueView from '$lib/components/QueueView.svelte';
  import { player } from '$lib/stores/player.svelte';
  import { Search, Library, ListMusic, Music2 } from 'lucide-svelte';

  type Tab = 'search' | 'library' | 'queue';
  let activeTab = $state<Tab>('search');
</script>

<div class="flex h-screen w-screen flex-col overflow-hidden bg-surface-base text-text-primary">
  <!-- Main Workspace -->
  <div class="flex flex-1 overflow-hidden">
    <!-- Sidebar Navigation -->
    <aside class="w-14 sm:w-56 bg-surface-elevated border-r border-surface-border flex flex-col justify-between p-2 sm:p-4 flex-shrink-0 select-none">
      <div class="space-y-6">
        <!-- Brand -->
        <div class="flex items-center gap-2.5 px-2 py-1">
          <div class="w-7 h-7 rounded-md bg-accent-subtle border border-surface-border flex items-center justify-center text-text-primary">
            <Music2 size={16} />
          </div>
          <span class="hidden sm:inline font-semibold text-sm tracking-tight text-text-primary">Kasane</span>
        </div>

        <!-- Navigation Links -->
        <nav class="space-y-1">
          <button
            onclick={() => (activeTab = 'search')}
            class="w-full flex items-center gap-3 px-3 py-2 rounded-lg text-sm font-medium transition {activeTab === 'search' ? 'bg-surface-hover text-text-primary' : 'text-text-secondary hover:text-text-primary hover:bg-surface-hover/50'}"
          >
            <Search size={16} />
            <span class="sr-only sm:not-sr-only">Search</span>
          </button>

          <button
            onclick={() => (activeTab = 'library')}
            class="w-full flex items-center gap-3 px-3 py-2 rounded-lg text-sm font-medium transition {activeTab === 'library' ? 'bg-surface-hover text-text-primary' : 'text-text-secondary hover:text-text-primary hover:bg-surface-hover/50'}"
          >
            <Library size={16} />
            <span class="sr-only sm:not-sr-only">Library</span>
          </button>

          <button
            onclick={() => (activeTab = 'queue')}
            class="w-full flex items-center gap-3 px-3 py-2 rounded-lg text-sm font-medium transition {activeTab === 'queue' ? 'bg-surface-hover text-text-primary' : 'text-text-secondary hover:text-text-primary hover:bg-surface-hover/50'}"
          >
            <ListMusic size={16} />
            <span class="sr-only sm:not-sr-only">Queue</span>
          </button>
        </nav>
      </div>

      <!-- App Status Footnote -->
      <div class="hidden sm:flex px-2 py-2 border-t border-surface-border/50 text-[11px] text-text-muted items-center justify-between">
        <span>v0.1.0</span>
        <span class="inline-flex items-center gap-1.5">
          <span class="w-1.5 h-1.5 rounded-full bg-emerald-500"></span>
          Ready
        </span>
      </div>
    </aside>

    <!-- Main Viewport -->
    <main class="flex-1 overflow-y-auto">
      <div hidden={activeTab !== 'search'}>
        <SearchView />
      </div>
      {#if activeTab === 'library'}
        <LibraryView />
      {:else if activeTab === 'queue'}
        <QueueView />
      {/if}
    </main>
  </div>

  <!-- Persistent Bottom Player Bar -->
  {#if player.error}
    <div class="fixed inset-x-4 bottom-24 z-[60] mx-auto flex max-w-xl items-center gap-3 rounded-lg border border-surface-border bg-surface-elevated p-3 text-sm shadow-lg">
      <p role="alert" class="flex-1 text-accent-highlight">{player.error}</p>
      <button class="text-text-secondary hover:text-text-primary" onclick={() => (player.error = null)}>Dismiss</button>
    </div>
  {/if}
  <PlayerBar />

  <!-- Fullscreen Modal View -->
  <NowPlayingView />
</div>
