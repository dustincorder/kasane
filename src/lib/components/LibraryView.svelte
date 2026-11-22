<script lang="ts">
  import { player } from '$lib/stores/player.svelte';
  import { view } from '$lib/stores/connect.svelte';
  import { account } from '$lib/stores/account.svelte';
  import { Play, Heart, Clock, FolderOpen, ListMusic, ChevronLeft } from 'lucide-svelte';

  let favorites = $derived(player.favorites);
  let history = $derived(player.history);
  let openPlaylist = $derived(account.playlists.find((p) => p.id === account.openPlaylistId));
</script>

<div class="p-6 max-w-5xl mx-auto space-y-8 select-none">
  <button
    onclick={() => player.openAudioFile()}
    disabled={player.isBusy}
    class="inline-flex items-center gap-2 rounded-lg border border-surface-border bg-surface-elevated px-4 py-2 text-sm text-text-primary hover:bg-surface-hover disabled:opacity-50"
  >
    <FolderOpen size={16} />
    Open audio file
  </button>

  <!-- YouTube Music account library (signed in only) -->
  {#if account.signedIn}
    <section class="space-y-3">
      <div class="flex items-center gap-2 text-text-primary font-semibold text-lg">
        <ListMusic size={18} class="text-text-primary" />
        <span>YouTube Music</span>
      </div>

      {#if openPlaylist}
        <div class="space-y-3">
          <div class="flex items-center gap-3">
            <button
              onclick={() => (account.openPlaylistId = null)}
              class="inline-flex items-center gap-1 text-sm text-text-secondary hover:text-text-primary transition"
            >
              <ChevronLeft size={14} />
              Back
            </button>
            <span class="text-sm font-medium text-text-primary truncate">{openPlaylist.name}</span>
            <button
              onclick={() => account.playInOrder(account.openPlaylistTracks)}
              disabled={player.isBusy || account.openPlaylistTracks.length === 0}
              class="ml-auto inline-flex items-center gap-2 rounded-lg bg-accent-highlight/90 hover:bg-accent-highlight px-3 py-1.5 text-sm font-medium text-white transition disabled:opacity-50"
            >
              <Play size={14} />
              Play all
            </button>
          </div>
          <div class="divide-y divide-surface-border/50 border border-surface-border rounded-lg overflow-hidden bg-surface-elevated">
            {#each account.openPlaylistTracks as track}
              <div class="flex items-center justify-between p-3 hover:bg-surface-hover transition group">
                <div class="flex items-center gap-3 min-w-0">
                  <div class="w-10 h-10 rounded-md bg-surface-base flex-shrink-0 overflow-hidden">
                    {#if track.thumbnail_url}
                      <img src={track.thumbnail_url} alt={track.title} class="w-full h-full object-cover" />
                    {:else}
                      <div class="w-full h-full flex items-center justify-center text-xs text-text-muted">♪</div>
                    {/if}
                  </div>
                  <div class="truncate">
                    <div class="text-sm font-medium text-text-primary truncate">{track.title}</div>
                    <div class="text-xs text-text-secondary truncate">{track.artist}</div>
                  </div>
                </div>
                <button
                  onclick={() => view.playTrack(track)}
                  disabled={player.isBusy}
                  class="p-2 bg-accent-active text-white rounded-full hover:opacity-90 active:scale-95 transition"
                  title="Play track"
                >
                  <Play size={14} fill="currentColor" class="translate-x-0.5" />
                </button>
              </div>
            {/each}
          </div>
        </div>
      {:else}
        <!-- Liked music -->
        <div class="space-y-2">
          <div class="flex items-center justify-between">
            <span class="text-sm text-text-secondary">Liked music ({account.liked.length})</span>
            {#if account.liked.length > 0}
              <button
                onclick={() => account.playInOrder(account.liked)}
                disabled={player.isBusy}
                class="inline-flex items-center gap-1.5 text-xs text-accent-highlight hover:opacity-80 transition disabled:opacity-50"
              >
                <Play size={12} />
                Play all
              </button>
            {/if}
          </div>
          {#if account.libraryLoading}
            <div class="p-6 border border-dashed border-surface-border rounded-lg text-center text-text-muted text-sm">Loading your library…</div>
          {:else if account.liked.length === 0}
            <div class="p-6 border border-dashed border-surface-border rounded-lg text-center text-text-muted text-sm">No liked music found.</div>
          {:else}
            <div class="divide-y divide-surface-border/50 border border-surface-border rounded-lg overflow-hidden bg-surface-elevated">
              {#each account.liked.slice(0, 25) as track}
                <div class="flex items-center justify-between p-3 hover:bg-surface-hover transition group">
                  <div class="flex items-center gap-3 min-w-0">
                    <div class="w-10 h-10 rounded-md bg-surface-base flex-shrink-0 overflow-hidden">
                      {#if track.thumbnail_url}
                        <img src={track.thumbnail_url} alt={track.title} class="w-full h-full object-cover" />
                      {:else}
                        <div class="w-full h-full flex items-center justify-center text-xs text-text-muted">♪</div>
                      {/if}
                    </div>
                    <div class="truncate">
                      <div class="text-sm font-medium text-text-primary truncate">{track.title}</div>
                      <div class="text-xs text-text-secondary truncate">{track.artist}</div>
                    </div>
                  </div>
                  <button
                    onclick={() => view.playTrack(track)}
                    disabled={player.isBusy}
                    class="p-2 bg-accent-active text-white rounded-full hover:opacity-90 active:scale-95 transition"
                    title="Play track"
                  >
                    <Play size={14} fill="currentColor" class="translate-x-0.5" />
                  </button>
                </div>
              {/each}
            </div>
          {/if}
        </div>

        <!-- Playlists grid -->
        {#if account.playlists.length > 0}
          <div class="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-3 pt-2">
            {#each account.playlists as playlist}
              <button
                onclick={() => account.openPlaylist(playlist.id)}
                class="bg-surface-elevated border border-surface-border rounded-lg p-3 text-left hover:bg-surface-hover transition group"
              >
                <div class="w-full aspect-square rounded-md bg-surface-base overflow-hidden mb-2">
                  {#if playlist.thumbnail_url}
                    <img src={playlist.thumbnail_url} alt={playlist.name} class="w-full h-full object-cover" />
                  {:else}
                    <div class="w-full h-full flex items-center justify-center text-text-muted"><ListMusic size={22} /></div>
                  {/if}
                </div>
                <div class="text-sm font-medium text-text-primary truncate">{playlist.name}</div>
                <div class="text-xs text-text-secondary">{playlist.track_count} tracks</div>
              </button>
            {/each}
          </div>
        {/if}
      {/if}
    </section>
  {/if}

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
              <div class="w-10 h-10 rounded-md bg-surface-base flex-shrink-0 overflow-hidden">
                {#if track.thumbnail_url}
                  <img src={track.thumbnail_url} alt={track.title} class="w-full h-full object-cover" />
                {:else}
                  <div class="w-full h-full flex items-center justify-center text-xs text-text-muted">♪</div>
                {/if}
              </div>
              <div class="truncate">
                <div class="text-sm font-medium text-text-primary truncate">{track.title}</div>
                <div class="text-xs text-text-secondary truncate">{track.artist}</div>
              </div>
            </div>

            <div class="flex items-center gap-2">
              <button
                onclick={() => view.playTrack(track)}
                disabled={player.isBusy}
                class="p-2 bg-accent-active text-white rounded-full hover:opacity-90 active:scale-95 transition"
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
              <div class="w-10 h-10 rounded-md bg-surface-base flex-shrink-0 overflow-hidden">
                {#if track.thumbnail_url}
                  <img src={track.thumbnail_url} alt={track.title} class="w-full h-full object-cover" />
                {:else}
                  <div class="w-full h-full flex items-center justify-center text-xs text-text-muted">♪</div>
                {/if}
              </div>
              <div class="truncate">
                <div class="text-sm font-medium text-text-primary truncate">{track.title}</div>
                <div class="text-xs text-text-secondary truncate">{track.artist}</div>
              </div>
            </div>

            <div class="flex items-center gap-3">
              <span class="text-xs tabular-nums text-text-muted">{track.duration_secs > 0 ? player.formatTime(track.duration_secs) : '—'}</span>
              <button
                onclick={() => view.playTrack(track)}
                disabled={player.isBusy}
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
