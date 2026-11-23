<script lang="ts">
  import DevicesView from '$lib/components/DevicesView.svelte';
  import { account } from '$lib/stores/account.svelte';
  import { Settings, User, MonitorSmartphone, HardDrive, LogOut } from 'lucide-svelte';

  $effect(() => {
    void account.refreshStorage();
  });
</script>

<div class="p-6 max-w-5xl mx-auto space-y-8 select-none">
  <div class="flex items-center gap-2 text-text-primary font-semibold text-lg">
    <Settings size={18} />
    <span>Settings</span>
  </div>

  <!-- Account -->
  <section class="space-y-3">
    <div class="flex items-center gap-2 text-text-primary font-medium text-sm uppercase tracking-wide">
      <User size={15} />
      <span>Account</span>
    </div>

    <div class="bg-surface-elevated border border-surface-border rounded-lg p-4">
      {#if account.signedIn}
        <div class="flex items-center gap-3">
          <div class="w-11 h-11 rounded-full bg-surface-base overflow-hidden flex-shrink-0">
            {#if account.status.avatar}
              <img src={account.status.avatar} alt="" class="w-full h-full object-cover" />
            {:else}
              <div class="w-full h-full flex items-center justify-center text-text-muted"><User size={18} /></div>
            {/if}
          </div>
          <div class="flex-1 min-w-0">
            <div class="text-sm font-medium text-text-primary truncate">{account.status.name ?? 'YouTube Music account'}</div>
            {#if account.status.channel_id}
              <div class="text-xs text-text-secondary truncate">{account.status.channel_id}</div>
            {/if}
            {#if account.status.persistence === 'memory'}
              <div class="text-xs text-accent-highlight mt-0.5">Session kept in memory only — Secret Service unavailable</div>
            {/if}
          </div>
          <button
            onclick={() => account.signOut()}
            class="inline-flex items-center gap-2 rounded-lg border border-surface-border px-3 py-1.5 text-sm text-text-secondary hover:text-text-primary hover:bg-surface-hover transition"
          >
            <LogOut size={14} />
            Sign out
          </button>
        </div>
      {:else}
        <div class="flex items-center gap-3">
          <div class="flex-1 min-w-0">
            <div class="text-sm text-text-primary">Sign in to YouTube Music</div>
            <div class="text-xs text-text-secondary mt-0.5">Liked music and your playlists land in Library. Sign-in happens in a separate Google window — Kasane never sees your password.</div>
          </div>
          <button
            onclick={() => account.signIn()}
            disabled={account.signingIn}
            class="rounded-lg bg-accent-highlight/90 hover:bg-accent-highlight px-4 py-2 text-sm font-medium text-white transition disabled:opacity-50"
          >
            {account.signingIn ? 'Waiting for Google…' : 'Sign in'}
          </button>
        </div>
        {#if account.error}
          <div class="text-xs text-accent-highlight mt-2">{account.error}</div>
        {/if}
      {/if}
    </div>
  </section>

  <!-- Kasane Connect -->
  <section class="space-y-3">
    <div class="flex items-center gap-2 text-text-primary font-medium text-sm uppercase tracking-wide">
      <MonitorSmartphone size={15} />
      <span>Kasane Connect</span>
    </div>

    <div class="bg-surface-elevated border border-surface-border rounded-lg p-4">
      <div class="flex items-center gap-3">
        <div class="flex-1 min-w-0">
          <div class="text-sm text-text-primary">Remote control over your LAN</div>
          <div class="text-xs text-text-secondary mt-0.5">
            {#if account.connectName}
              This device: <span class="text-text-primary">{account.connectName}</span>
            {:else}
              This device
            {/if}
          </div>
        </div>
        <button
          role="switch"
          aria-checked={account.connectEnabled}
          aria-label="Kasane Connect"
          onclick={() => account.setConnectEnabled(!account.connectEnabled)}
          class="relative inline-flex h-6 w-11 flex-shrink-0 items-center rounded-full transition {account.connectEnabled ? 'bg-accent-highlight' : 'bg-surface-hover'}"
        >
          <span class="inline-block h-4 w-4 transform rounded-full bg-white transition {account.connectEnabled ? 'translate-x-6' : 'translate-x-1'}"></span>
        </button>
      </div>
    </div>
    {#if account.connectEnabled}
      <DevicesView />
    {/if}
  </section>

  <!-- Storage -->
  <section class="space-y-3">
    <div class="flex items-center gap-2 text-text-primary font-medium text-sm uppercase tracking-wide">
      <HardDrive size={15} />
      <span>Storage</span>
    </div>

    <div class="bg-surface-elevated border border-surface-border rounded-lg p-4 space-y-2">
      {#if account.storage}
        <div class="flex items-baseline justify-between gap-3">
          <span class="text-sm text-text-primary">Library database</span>
          <span class="text-xs text-text-secondary">{account.formatBytes(account.storage.database_bytes)}</span>
        </div>
        <div class="text-[11px] text-text-muted break-all">{account.storage.database_path}</div>
        <div class="flex items-baseline justify-between gap-3 pt-1">
          <span class="text-sm text-text-primary">Connect data</span>
          <span class="text-xs text-text-secondary">{account.formatBytes(account.storage.connect_bytes)}</span>
        </div>
        <div class="text-[11px] text-text-muted break-all">{account.storage.app_data_dir}</div>
      {:else}
        <div class="text-sm text-text-secondary">Reading storage…</div>
      {/if}
    </div>
  </section>
</div>
