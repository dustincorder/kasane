<script lang="ts">
  import { connect } from '$lib/stores/connect.svelte';
  import { ShieldCheck } from 'lucide-svelte';
</script>

{#each connect.prompts as prompt (prompt.prompt_id)}
  <div class="fixed inset-0 z-[70] flex items-center justify-center bg-black/50">
    <div class="w-80 rounded-xl border border-surface-border bg-surface-elevated p-5 shadow-2xl select-none">
      <div class="flex items-center gap-2 mb-3">
        <ShieldCheck size={18} class="text-accent-highlight" />
        <h2 class="text-sm font-semibold text-text-primary">
          {prompt.incoming ? 'Pairing request' : 'Confirm pairing'}
        </h2>
      </div>
      <p class="text-xs text-text-secondary mb-4">
        {#if prompt.incoming}
          <span class="text-text-primary font-medium">{prompt.peer_name}</span> wants to pair with this device.
        {:else}
          Pairing with <span class="text-text-primary font-medium">{prompt.peer_name}</span>.
        {/if}
        Check that this code matches on both screens, then confirm.
      </p>
      <div class="text-center text-3xl font-semibold tracking-[0.4em] text-accent-highlight tabular-nums py-3 mb-4">
        {prompt.code}
      </div>
      <div class="flex justify-end gap-2">
        <button
          class="px-3 py-1.5 text-xs rounded-lg bg-surface-hover text-text-secondary hover:bg-surface-border transition"
          onclick={() => connect.confirm(prompt.prompt_id, false)}
        >Reject</button>
        <button
          class="px-3 py-1.5 text-xs rounded-lg bg-accent-active text-white hover:opacity-90 transition"
          onclick={() => connect.confirm(prompt.prompt_id, true)}
        >Confirm</button>
      </div>
    </div>
  </div>
{/each}
