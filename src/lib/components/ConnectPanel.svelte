<script lang="ts">
  import { connect } from '$lib/stores/connect.svelte';
  import { player } from '$lib/stores/player.svelte';
  import { Monitor, Smartphone, MoreVertical, Volume1, Volume2, Plus, X } from 'lucide-svelte';

  let { onAddDevice }: { onAddDevice: () => void } = $props();
  let menuFor = $state<string | null>(null);
  let forgetting = $state<string | null>(null);

  const online = $derived(
    connect.devices.filter((d) => d.trusted && d.id && d.id in connect.remotes),
  );
  const current = $derived(connect.target ? online.find((d) => d.id === connect.target) ?? null : null);

  function icon(platform: string | null) {
    return platform === 'android' ? Smartphone : Monitor;
  }
  function trackLine(t: { title: string; artist: string } | null | undefined) {
    return t ? [t.title, t.artist].filter(Boolean).join(' — ') : null;
  }
  const currentLine = $derived(
    current ? trackLine(connect.remotes[current.id!]?.frame.track) : trackLine(player.currentTrack),
  );
  const CurIcon = $derived(icon(current?.platform ?? 'linux'));
  const volume = $derived(current ? connect.remoteVolume : player.volume);

  function setVolume(e: Event) {
    const v = parseFloat((e.target as HTMLInputElement).value);
    if (current) void connect.remoteSetVolume(v);
    else void player.setVolume(v);
  }
  function pick(id: string | null) {
    void connect.select(id);
  }
</script>

<aside class="fixed right-0 top-0 bottom-[76px] z-40 w-80 border-l border-surface-border bg-surface-elevated shadow-2xl flex flex-col select-none" aria-label="Connect">
  <div class="flex items-center justify-between px-4 py-3">
    <h2 class="text-sm font-semibold text-text-primary">Connect</h2>
    <button class="p-1 text-text-muted hover:text-text-primary" onclick={() => (connect.showDevices = false)} aria-label="Close"><X size={16} /></button>
  </div>

  {#if connect.error}
    <p role="alert" class="px-4 pb-2 text-xs text-accent-highlight">{connect.error}</p>
  {/if}

  <!-- The device that plays now -->
  <div class="mx-3 rounded-xl bg-surface-hover p-4">
    <div class="flex items-start gap-3">
      <div class="flex-1 min-w-0">
        <div class="text-sm font-semibold text-text-primary truncate">{current?.name ?? connect.local?.name ?? 'This PC'}{current ? '' : ' (this PC)'}</div>
        <div class="text-xs text-accent-highlight truncate">{currentLine ?? 'Ready'}</div>
      </div>
      <CurIcon size={26} class="text-accent-highlight flex-shrink-0" />
    </div>
    <div class="flex items-center gap-2 mt-3">
      <Volume1 size={14} class="text-text-muted" />
      <input type="range" min="0" max="1" step="0.01" value={volume} onchange={setVolume} aria-label="Volume"
        class="flex-1 h-1 bg-surface-border rounded-lg appearance-none cursor-pointer accent-accent-highlight" />
      <Volume2 size={14} class="text-text-muted" />
    </div>
  </div>

  <div class="flex-1 overflow-y-auto px-2 py-2">
    {#if current}
      <button class="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg hover:bg-surface-hover text-left" onclick={() => pick(null)} disabled={!!connect.transferring}>
        <Monitor size={18} class="text-text-secondary" />
        <span class="flex-1 text-sm text-text-primary truncate">{connect.local?.name ?? 'This PC'} (this PC)</span>
      </button>
    {/if}
    {#each online.filter((d) => d.id !== connect.target) as d (d.id)}
      {@const Icon = icon(d.platform)}
      <div class="relative flex items-center gap-1 rounded-lg hover:bg-surface-hover">
        <button class="flex-1 min-w-0 flex items-center gap-3 px-3 py-2.5 text-left" onclick={() => pick(d.id)} disabled={!!connect.transferring}>
          <Icon size={18} class="text-text-secondary flex-shrink-0" />
          <span class="min-w-0">
            <span class="block text-sm text-text-primary truncate">{d.name}</span>
            {#if connect.transferring === d.id}
              <span class="block text-xs text-text-muted">Moving playback…</span>
            {:else if connect.remotes[d.id!]?.frame.track}
              <span class="block text-xs text-text-muted truncate">{trackLine(connect.remotes[d.id!].frame.track)}</span>
            {/if}
          </span>
        </button>
        <button class="p-2 text-text-muted hover:text-text-primary" aria-label="Device options" onclick={() => (menuFor = menuFor === d.id ? null : d.id)}>
          <MoreVertical size={16} />
        </button>
        {#if menuFor === d.id}
          <div class="absolute right-2 top-10 z-50 w-40 rounded-lg border border-surface-border bg-surface-base py-1 shadow-xl">
            <button class="w-full px-3 py-2 text-left text-xs text-text-primary hover:bg-surface-hover" onclick={() => { menuFor = null; void connect.disconnect(d.id!); }}>Disconnect</button>
            <button class="w-full px-3 py-2 text-left text-xs text-accent-highlight hover:bg-surface-hover" onclick={() => { menuFor = null; forgetting = d.id; }}>Forget device</button>
          </div>
        {/if}
      </div>
      {#if forgetting === d.id}
        <div class="mx-3 mb-2 rounded-lg border border-surface-border p-3 text-xs text-text-secondary">
          Forget {d.name}? It will need a new pairing code.
          <div class="mt-2 flex gap-2 justify-end">
            <button class="px-2.5 py-1 rounded bg-surface-hover" onclick={() => (forgetting = null)}>Cancel</button>
            <button class="px-2.5 py-1 rounded bg-accent-active text-white" onclick={() => { forgetting = null; void connect.remove(d.id!); }}>Forget</button>
          </div>
        </div>
      {/if}
    {/each}
    {#if online.length === 0}
      <p class="px-3 py-4 text-xs text-text-muted">No other devices are online. Open Kasane on another paired device on this network.</p>
    {/if}
  </div>

  <button class="m-3 flex items-center justify-center gap-2 rounded-lg border border-surface-border px-3 py-2 text-xs text-text-secondary hover:text-text-primary hover:bg-surface-hover"
    onclick={() => { connect.showDevices = false; onAddDevice(); }}>
    <Plus size={14} /> Add a device
  </button>
</aside>
