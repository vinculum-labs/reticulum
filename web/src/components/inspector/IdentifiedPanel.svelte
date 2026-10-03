<script lang="ts">
  import Help from "./Help.svelte";
  import { app, identifyHex, setIdentifies, setIdentify } from "../../lib/state.svelte";
  import type { Identify } from "../../lib/types";
  import InspectorPanel from "./InspectorPanel.svelte";

  const identified = $derived(app.snapshot?.identified ?? []);
  const canEdit = $derived(app.snapshot?.canEdit ?? false);

  function recolor(item: Identify, value: string) {
    setIdentify(item, true, parseInt(value.slice(1), 16));
  }
</script>

<InspectorPanel title="Identified" onclose={() => (app.panel = null)}>
  <Help>
    Lit up in the world for everyone who can see this network: an outline through walls, the name above it, and a beam from far away. They
    stay on until turned off. Click a color to change it.
  </Help>
  {#each identified as item (item.kind + (item.connector ?? "") + JSON.stringify(item.device ?? "") + (item.face ?? ""))}
    <div class="flex items-center gap-2 text-sm">
      <input
        type="color"
        class="swatch h-7 w-8 shrink-0 cursor-pointer rounded border p-0.5"
        value={identifyHex(item.color)}
        disabled={!canEdit}
        title="Change its color"
        aria-label={`Color of ${item.label}`}
        onchange={(e) => recolor(item, e.currentTarget.value)}
      />
      <span class="min-w-0 flex-1 truncate" title={item.label}>{item.label}</span>
      <button class="btn text-sm" disabled={!canEdit} onclick={() => setIdentify(item, false)}>Turn off</button>
    </div>
  {:else}
    <p class="text-sm muted">Nothing is identified. Use the ID buttons on devices and faces, or the network tool's Identify mode in game.</p>
  {/each}
  {#if identified.length > 1 && canEdit}
    <button class="btn btn-danger self-start text-sm" onclick={() => setIdentifies(identified, false)}>Turn all off</button>
  {/if}
</InspectorPanel>

<style>
  .swatch {
    border-color: var(--line);
    background: var(--field);
  }
</style>
