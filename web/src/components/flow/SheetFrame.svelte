<script lang="ts">
  import { addSheetPin, app } from "../../lib/state.svelte";

  /**
   * The open sheet as a frame around its nodes: its pins sit on its edges (inputs left, outputs right). Only a
   * backdrop: it can't be selected or moved.
   */
  let { data }: { data: { sheetId: string; name: string; width: number; height: number; canAdd: boolean } } = $props();

  function add(input: boolean) {
    const sheet = app.snapshot?.sheets.find((s) => s.id === data.sheetId);
    if (sheet) addSheetPin(sheet, input);
  }
</script>

<div class="sheet-frame rounded-lg" style="width: {data.width}px; height: {data.height}px">
  <!-- The title line, set apart like a card's header (its accent on the left, a line below). -->
  <div class="frame-header flex items-center gap-2 px-2 py-1 text-xs muted">
    <span class="min-w-0 flex-1 truncate font-medium">{data.name}</span>
    {#if data.canAdd}
      <!-- Each shows the pin it adds: an input's dot is solid (it feeds what is inside), an output's a ring. -->
      <button class="nodrag btn flex items-center gap-1 px-1.5 py-0 text-xs" title="Add a pin on the left edge: where things come into this sheet" onclick={() => add(true)}>
        <span class="pin-icon solid" aria-hidden="true"></span>input
      </button>
      <button class="nodrag btn flex items-center gap-1 px-1.5 py-0 text-xs" title="Add a pin on the right edge: where things leave this sheet" onclick={() => add(false)}>
        <span class="pin-icon" aria-hidden="true"></span>output
      </button>
    {/if}
  </div>
</div>

<style>
  .sheet-frame {
    border: 2px dashed var(--line);
    background: color-mix(in srgb, var(--panel) 25%, transparent);
    pointer-events: none;
  }
  .sheet-frame button {
    pointer-events: auto;
  }
  .pin-icon {
    width: 10px;
    height: 10px;
    border-radius: 50%;
    border: 2px solid currentColor;
    flex-shrink: 0;
  }
  .pin-icon.solid {
    background: currentColor;
  }
  .frame-header {
    border-bottom: 1px solid var(--line);
    border-left: 3px solid var(--muted);
    border-top-left-radius: 6px;
    background: color-mix(in srgb, var(--panel) 70%, transparent);
  }
</style>
