<script lang="ts">
  import { connect } from '$lib/stores/connect.svelte';
  import { Smartphone, Monitor, Plus, Trash2 } from 'lucide-svelte';

  let manualAddr = $state('');
  let addingManual = $state(false);
  /** Device id awaiting a second click to confirm "Forget". */
  let forgetting = $state<string | null>(null);

  function platformIcon(platform: string | null) {
    return platform === 'android' ? Smartphone : Monitor;
  }

  function statusLabel(device: { online: boolean; connection: string; trusted: boolean }) {
    if (device.connection === 'paired') return 'paired';
    if (device.connection === 'connected' || device.connection === 'connecting') return device.connection;
    if (device.online) return device.trusted ? 'online' : 'found';
    return device.trusted ? 'offline' : 'unavailable';
  }

  async function addManual() {
    const target = manualAddr.trim();
    if (!target) return;
    addingManual = true;
    await connect.pair(target);
    addingManual = false;
    manualAddr = '';
  }
</script>

<!-- Pairing and paired-device management, shown in Settings → Kasane Connect. -->
<div class="space-y-4 select-none">

  {#if connect.error}
    <p role="alert" class="text-sm text-accent-highlight">{connect.error}</p>
  {/if}

  <!-- This device -->
  {#if connect.local}
    <div class="border border-surface-border rounded-lg bg-surface-elevated p-4 flex items-center gap-3">
      <Monitor size={18} class="text-accent-highlight flex-shrink-0" />
      <div class="flex-1 min-w-0">
        <div class="text-sm font-medium text-text-primary truncate">{connect.local.name} (this PC)</div>
        <div class="text-xs text-text-muted">
          {connect.local.platform} &bull; port {connect.local.port}
        </div>
      </div>
      <span class="text-xs text-text-muted">Listening on the LAN</span>
    </div>
  {/if}

  <!-- Devices -->
  <div class="divide-y divide-surface-border/50 border border-surface-border rounded-lg overflow-hidden bg-surface-elevated">
    {#if connect.devices.length === 0}
      <div class="p-8 text-center text-sm text-text-muted">
        No devices found yet. Open Kasane on another device on this network, or pair one by address below. Each pair of devices is paired once.
      </div>
    {/if}
    {#each connect.devices as device (device.id ?? device.addr ?? device.name)}
      {@const Icon = platformIcon(device.platform)}
      <div class="flex items-center gap-3 p-3">
        <Icon size={18} class="text-text-secondary flex-shrink-0" />
        <div class="flex-1 min-w-0">
          <div class="text-sm font-medium text-text-primary truncate">{device.name}</div>
          <div class="text-xs text-text-muted truncate">
            {device.platform ?? 'unknown'} &bull; {statusLabel(device)}
            {#if device.addr}&bull; {device.addr}{/if}
            {#if device.last_error}&bull; {device.last_error}{/if}
          </div>
        </div>
        <div class="flex items-center gap-2 flex-shrink-0">
          {#if !device.trusted && (device.id || device.addr)}
            <button
              class="px-2.5 py-1 text-xs rounded bg-surface-hover text-text-primary hover:bg-surface-border transition"
              onclick={() => connect.pair(device.id ?? device.addr ?? '')}
              disabled={!device.online}
            >Pair</button>
          {:else if device.trusted && device.id}
            {#if forgetting === device.id}
              <button
                class="px-2.5 py-1 text-xs rounded bg-accent-active text-white"
                onclick={() => { forgetting = null; if (device.id) connect.remove(device.id); }}
              >Forget</button>
              <button
                class="px-2.5 py-1 text-xs rounded bg-surface-hover text-text-secondary"
                onclick={() => (forgetting = null)}
              >Cancel</button>
            {:else}
              <button
                class="p-1.5 text-text-muted hover:text-accent-highlight transition"
                onclick={() => (forgetting = device.id)}
                aria-label="Forget device"
                title="Forget device"
              ><Trash2 size={14} /></button>
            {/if}
          {/if}
        </div>
      </div>
    {/each}
  </div>

  <!-- Manual add -->
  <div class="flex items-center gap-2">
    <input
      type="text"
      bind:value={manualAddr}
      placeholder="Add by address, e.g. 10.10.10.5"
      class="flex-1 max-w-xs bg-surface-elevated border border-surface-border rounded-lg px-3 py-2 text-sm text-text-primary placeholder:text-text-muted focus:outline-none focus:border-accent-highlight"
      onkeydown={(e) => e.key === 'Enter' && addManual()}
    />
    <button
      class="px-3 py-2 text-xs rounded-lg bg-surface-hover text-text-primary hover:bg-surface-border transition flex items-center gap-1.5 disabled:opacity-40"
      onclick={addManual}
      disabled={addingManual || !manualAddr.trim()}
    ><Plus size={14} />Add</button>
    <span class="text-xs text-text-muted">LAN addresses only</span>
  </div>
</div>
