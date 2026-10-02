<script lang="ts">
  import Power from "@lucide/svelte/icons/power";
  import { app, edit, setSheetEnabled } from "../../lib/state.svelte";
  import type { Sheet, Snapshot } from "../../lib/types";

  /** Says on the canvas when the open sheet isn't running and why, so a sheet left off isn't mistaken for a broken one. */
  let { sheet, snapshot }: { sheet: Sheet; snapshot: Snapshot } = $props();

  const canEdit = $derived(snapshot.canEdit);
  /** The outermost folder holding this sheet that is switched off. */
  const offFolder = $derived.by(() => {
    const parts = (sheet.folder ?? "").split("/").filter(Boolean);
    for (let i = 1; i <= parts.length; i++) {
      const path = parts.slice(0, i).join("/");
      if (snapshot.disabledFolders.includes(path)) return path;
    }
    return null;
  });
  const why = $derived(
    sheet.subsheet
      ? null
      : !snapshot.enabled
        ? "network"
        : sheet.enabled === false
          ? "sheet"
          : offFolder !== null
            ? "folder"
            : app.sheetsOff.includes(sheet.id)
              ? "redstone"
              : null,
  );
  /** Redstone holding the sheet off: its own condition, else the network's. */
  const sheetRedstone = $derived(!!sheet.redstone?.mode && sheet.redstone.mode !== "ignored");

  function switchOn() {
    if (why === "network") {
      edit([{ type: "setNetworkEnabled", enabled: true }], [{ type: "setNetworkEnabled", enabled: false }], "network on/off");
    } else if (why === "sheet") {
      setSheetEnabled(sheet, true);
    } else if (why === "folder" && offFolder !== null) {
      edit([{ type: "setFolderEnabled", folder: offFolder, enabled: true }], [{ type: "setFolderEnabled", folder: offFolder, enabled: false }], "folder on/off");
    }
  }

  function showCondition() {
    app.selectedNodes = [];
    app.selectedEdges = [];
    app.selectedDevice = null;
    app.panel = sheetRedstone ? null : "network";
  }
</script>

{#if why}
  <div class="box box-warn flex items-center gap-2 text-sm" style="background: var(--panel)" role="status">
    <span class="shrink-0 text-warn"><Power size={16} /></span>
    <span>
      {#if why === "network"}The network is switched off, so nothing on this sheet runs.
      {:else if why === "sheet"}This sheet is switched off.
      {:else if why === "folder"}This sheet is off: its folder “{offFolder}” is switched off.
      {:else}Redstone is holding this sheet off ({sheetRedstone ? "its own condition" : "the network's condition"}).{/if}
    </span>
    {#if why === "redstone"}
      <button class="btn shrink-0 text-xs" onclick={showCondition}>Show condition</button>
    {:else if canEdit}
      <button class="btn shrink-0 text-xs" onclick={switchOn}>Switch on</button>
    {/if}
  </div>
{/if}
