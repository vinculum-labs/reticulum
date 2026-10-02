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
  <div class="flex items-center gap-2 px-2 py-1 text-xs muted">
    <span class="min-w-0 flex-1 truncate font-medium">{data.name}</span>
    {#if data.canAdd}
      <button class="nodrag btn px-1.5 py-0 text-xs" title="A pin on the left edge: where things come into this sheet" onclick={() => add(true)}>+ input</button>
      <button class="nodrag btn px-1.5 py-0 text-xs" title="A pin on the right edge: where things leave this sheet" onclick={() => add(false)}>+ output</button>
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
</style>
