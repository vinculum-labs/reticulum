<script lang="ts">
  import Power from "@lucide/svelte/icons/power";
  import { app, edit, setSheetEnabled, showNode } from "../../lib/state.svelte";
  import { offReason } from "../../lib/tree";
  import type { Sheet, Snapshot } from "../../lib/types";

  /** Says on the canvas when the open sheet isn't running and why, so a sheet left off isn't mistaken for a broken one. */
  let { sheet, snapshot }: { sheet: Sheet; snapshot: Snapshot } = $props();

  const canEdit = $derived(snapshot.canEdit);
  const why = $derived(offReason(snapshot, sheet, app.sheetsOff));

  function switchOn() {
    if (why?.kind === "network") {
      edit([{ type: "setNetworkEnabled", enabled: true }], [{ type: "setNetworkEnabled", enabled: false }], "network on/off");
    } else if (why?.kind === "self") {
      setSheetEnabled(sheet, true);
    } else if (why?.kind === "parent") {
      setSheetEnabled(why.sheet, true);
    }
  }

  /** The node whose gate holds this sheet off: its own, or the node of the sheet above it that is gated. */
  function showGate() {
    if (why?.kind === "gate" && why.sheet.parent && why.sheet.node) showNode(why.sheet.parent, why.sheet.node);
  }
</script>

{#if why}
  <div class="box box-warn flex items-center gap-2 text-sm" style="background: var(--panel)" role="status">
    <span class="shrink-0 text-warn"><Power size={16} /></span>
    <span>
      {#if why.kind === "network"}The network is switched off, so nothing on this sheet runs.
      {:else if why.kind === "self"}This sheet is switched off.
      {:else if why.kind === "parent"}This sheet is off: the sheet “{why.sheet.name}” above it is switched off.
      {:else if why.sheet.id === sheet.id}A signal into this sheet's node's gate holds it off.
      {:else}This sheet is off: a signal into the gate of “{why.sheet.name}” above it holds that sheet off.{/if}
    </span>
    {#if why.kind === "gate"}
      {#if why.sheet.parent}<button class="btn shrink-0 text-xs" onclick={showGate}>Show its node</button>{/if}
    {:else if canEdit}
      <button class="btn shrink-0 text-xs" onclick={switchOn}>Switch on</button>
    {/if}
  </div>
{/if}
