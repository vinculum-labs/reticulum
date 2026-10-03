<script lang="ts">
  import { addSheetPin, app } from "../../lib/state.svelte";
  import PinMark from "../controls/PinMark.svelte";
  import { KIND_ICON } from "../../lib/icons";

  /**
   * The open sheet as a frame around its nodes: its pins sit on its edges (inputs left, outputs right). Only a
   * backdrop: it can't be selected or moved.
   */
  let { data }: { data: { sheetId: string; name: string; blueprint: boolean; width: number; height: number; canAdd: boolean } } = $props();

  /** The sheet is what the side panel shows when nothing else is selected (no node, wire, device or other panel). */
  const shown = $derived(!app.selectedNodes.length && !app.selectedEdges.length && !app.selectedDevice && !app.panel);


  /** Clicking the title line selects the sheet: its settings in the side panel. */
  function select() {
    app.selectedNodes = [];
    app.selectedEdges = [];
    app.selectedDevice = null;
    app.panel = null;
  }

  const KindIcon = $derived(KIND_ICON[data.blueprint ? "copy" : "sheet"]);

  function add(input: boolean) {
    const sheet = app.snapshot?.sheets.find((s) => s.id === data.sheetId);
    if (sheet) addSheetPin(sheet, input);
  }

</script>

<div class="sheet-frame rounded-lg" style="width: {data.width}px; height: {data.height}px">
  <!-- The title line, set apart like a card's header (its accent on the left, a line below). -->
  <!-- svelte-ignore a11y_click_events_have_key_events -->
  <div
    class="frame-header flex items-center gap-2 px-2 py-1 text-xs muted"
    class:shown
    role="button"
    tabindex="-1"
    title="Show this sheet's settings"
    onclick={select}
  >
    <!-- A sheet's or a blueprint's icon (an open copy shows its blueprint), as on their cards and in the toolbar. -->
    <span class="shrink-0"><KindIcon size={14} /></span>
    <span class="trim-text min-w-0 flex-1 truncate font-medium">{data.name}</span>
    {#if data.canAdd}
      <!-- Each shows the pin it adds: an input's dot is solid (it feeds what is inside), an output's a ring. -->
      <button class="nodrag btn flex items-center gap-1 px-1.5 py-0 text-xs" title="Add a pin on the left edge: where things come into this sheet"
        onclick={(e) => (e.stopPropagation(), add(true))}>
        <PinMark solid />input
      </button>
      <button class="nodrag btn flex items-center gap-1 px-1.5 py-0 text-xs" title="Add a pin on the right edge: where things leave this sheet"
        onclick={(e) => (e.stopPropagation(), add(false))}>
        <PinMark />output
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
  .frame-header {
    border-bottom: 1px solid var(--line);
    border-left: 3px solid var(--muted);
    border-top-left-radius: 6px;
    background: color-mix(in srgb, var(--panel) 70%, transparent);
    pointer-events: auto;
    cursor: pointer;
  }
  .frame-header.shown {
    border-left-color: var(--accent);
  }
</style>
