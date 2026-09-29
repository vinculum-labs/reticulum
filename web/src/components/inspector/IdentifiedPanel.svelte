<script lang="ts">
  import { app, edit, setIdentify } from "../../lib/state.svelte";
  import type { Identify } from "../../lib/types";

  const identified = $derived(app.snapshot?.identified ?? []);
  const canEdit = $derived(app.snapshot?.canEdit ?? false);

  function hex(color: number | undefined): string {
    return `#${(color ?? 0xffc830).toString(16).padStart(6, "0")}`;
  }

  function recolor(item: Identify, value: string) {
    setIdentify(item, true, parseInt(value.slice(1), 16));
  }
</script>

<div class="flex flex-col gap-3">
  <div class="flex items-center justify-between">
    <h2 class="text-base font-semibold">Identified</h2>
    <button class="btn text-sm" onclick={() => (app.panel = null)}>Close</button>
  </div>
  <p class="text-xs muted">
    Lit up in the world for everyone who can see this network: an outline through walls, the name above it, and a beam from far away. They
    stay on until turned off.
  </p>
  {#each identified as item (item.kind + (item.connector ?? "") + JSON.stringify(item.device ?? "") + (item.face ?? ""))}
    <div class="flex items-center gap-2 text-sm">
      <input
        type="color"
        class="h-6 w-7 shrink-0 cursor-pointer rounded border-0 p-0"
        value={hex(item.color)}
        disabled={!canEdit}
        title="Color"
        onchange={(e) => recolor(item, e.currentTarget.value)}
      />
      <span class="min-w-0 flex-1 truncate" title={item.label}>{item.label}</span>
      <button class="btn text-sm" disabled={!canEdit} onclick={() => setIdentify(item, false)}>Turn off</button>
    </div>
  {:else}
    <p class="text-sm muted">Nothing is identified. Use the ID buttons on devices and faces, or the network tool's Identify mode in game.</p>
  {/each}
  {#if identified.length > 1 && canEdit}
    <button class="btn btn-danger self-start text-sm" onclick={() => edit([{ type: "clearIdentify" }])}>Turn all off</button>
  {/if}
</div>
