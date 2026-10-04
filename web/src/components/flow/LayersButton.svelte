<script lang="ts">
  import LayersIcon from "@lucide/svelte/icons/layers";
  import { app, focusLayer, LAYERS, setLayers, type Layer, type LayerMode } from "../../lib/state.svelte";
  import { RESOURCE_LABEL, RESOURCE_VAR } from "../../lib/kinds";
  import Popover from "../controls/Popover.svelte";
  import Segmented from "../controls/Segmented.svelte";

  /**
   * The canvas's layers: per kind of wire, shown, dimmed (faint, still clickable) or hidden (its pins dimmed and marked
   * where a wire is hidden). A view of the sheet, remembered in this browser.
   */
  let open = $state(false);
  let button: HTMLButtonElement | undefined = $state();
  const changed = $derived(LAYERS.filter((l) => app.layers[l] !== "show").length);

  function color(layer: Layer): string {
    return layer === "redstone" ? "var(--c-signal)" : layer === "me" ? "var(--c-me)" : RESOURCE_VAR[layer];
  }
</script>

<button
  bind:this={button}
  class="btn flex items-center gap-1 text-sm"
  aria-pressed={changed > 0}
  aria-haspopup="dialog"
  title="Show, dim or hide wires by what they carry (remembered in this browser)"
  onclick={() => (open = !open)}><LayersIcon size={14} />Layers{changed ? ` (${changed} off)` : ""}</button
>
<Popover bind:open anchor={button} width={300} label="Layers" role="dialog">
  <!-- A set width: the note below wraps instead of widening the panel. -->
  <div class="flex flex-col gap-2 p-3 text-sm" style="width: 300px">
    {#each LAYERS as layer (layer)}
      <div class="flex items-center gap-2">
        <span class="swatch shrink-0" style="background: {color(layer)}"></span>
        <span class="min-w-0 flex-1 truncate">{RESOURCE_LABEL[layer]}</span>
        <Segmented
          label={`${RESOURCE_LABEL[layer]} wires`}
          size="xs"
          value={app.layers[layer]}
          options={[
            { value: "show", label: "Show" },
            { value: "dim", label: "Dim" },
            { value: "hide", label: "Hide" },
          ]}
          onchange={(mode) => setLayers({ [layer]: mode as LayerMode })}
        />
      </div>
    {/each}
    <div class="flex gap-2 pt-1">
      <button class="btn text-xs" disabled={!changed} onclick={() => focusLayer(null)}>Show all</button>
    </div>
    <p class="text-xs muted">Hidden wires come back faint while you draw a new wire, or select a card they join. Pins with a hidden wire get a ring.</p>
  </div>
</Popover>

<style>
  .swatch {
    width: 10px;
    height: 10px;
    border-radius: 50%;
  }
</style>
