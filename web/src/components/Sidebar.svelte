<script lang="ts">
  import ChevronDown from "@lucide/svelte/icons/chevron-down";
  import ChevronRight from "@lucide/svelte/icons/chevron-right";
  import Folder from "@lucide/svelte/icons/folder";
  import Layers from "@lucide/svelte/icons/layers";
  import TriangleAlert from "@lucide/svelte/icons/triangle-alert";
  import Zap from "@lucide/svelte/icons/zap";
  import { app, deviceUses, edit, deviceName, matchesDevice, openDevice, openSheet, setSheetEnabled, toast } from "../lib/state.svelte";
  import Segmented from "./controls/Segmented.svelte";
  import { confirmDialog, promptDialog } from "../lib/dialog.svelte";
  import { posKey, type Sheet } from "../lib/types";
  import DeviceIcon from "./DeviceIcon.svelte";
  import IdentifyBadge from "./IdentifyBadge.svelte";
  import FilterField from "./inspector/FilterField.svelte";
  import RemoveButton from "./controls/RemoveButton.svelte";
  import TextField from "./controls/TextField.svelte";
  import Switch from "./controls/Switch.svelte";

  const snapshot = $derived(app.snapshot!);
  const canEdit = $derived(snapshot.canEdit);

  let newSheet = $state("");
  let deviceSearch = $state("");
  /** Show every device, or only those no sheet uses yet (remembered per browser). */
  let deviceFilter = $state<"all" | "unused">(storedDeviceFilter());
  const uses = $derived(deviceUses(snapshot));
  const unusedCount = $derived(snapshot.devices.filter((d) => !uses.has(posKey(d.pos))).length);

  function storedDeviceFilter(): "all" | "unused" {
    try {
      return localStorage.getItem("reticulum.deviceFilter") === "unused" ? "unused" : "all";
    } catch {
      return "all";
    }
  }

  function setDeviceFilter(filter: "all" | "unused") {
    deviceFilter = filter;
    try {
      localStorage.setItem("reticulum.deviceFilter", filter);
    } catch {
      // Private windows and blocked storage: the choice lasts until the page closes.
    }
  }
  let renaming = $state<string | null>(null);
  let newFilterName = $state("");
  let newFilterExpr = $state("");

  const devices = $derived(
    snapshot.devices
      .filter((d) => matchesDevice(d, deviceSearch) && (deviceFilter === "all" || !uses.has(posKey(d.pos))))
      .sort((a, b) => deviceName(a).localeCompare(deviceName(b))),
  );

  async function addSheet() {
    const name = newSheet.trim() || `Sheet ${snapshot.sheets.length + 1}`;
    newSheet = "";
    const results = await edit([{ type: "createSheet", name }]);
    if (results?.[0]) app.sheetId = results[0] as string;
  }

  /** Pastes a sheet copied with "Copy as JSON" (from this or another world) as a new sheet. */
  async function importSheet() {
    const text = await promptDialog({
      title: "Import sheet",
      message: "Paste a sheet copied with “Copy as JSON”, from this world or another.",
      confirm: "Import",
      multiline: true,
    });
    if (!text?.trim()) return;
    let sheet: unknown;
    try {
      sheet = JSON.parse(text);
    } catch {
      toast("That isn't a copied sheet (not JSON).");
      return;
    }
    const results = await edit([{ type: "importSheet", sheet }]);
    if (results?.[0]) {
      app.sheetId = results[0] as string;
      toast("Imported. Devices from another world show as offline until you pick local ones for them.", "info");
    }
  }

  function renameSheet(sheet: Sheet, name: string) {
    edit([{ type: "updateSheet", sheet: sheet.id, name }], [{ type: "updateSheet", sheet: sheet.id, name: sheet.name }], "rename sheet");
  }

  async function deleteSheet(sheet: Sheet) {
    const nodes = sheet.nodes?.length ?? 0;
    const ok = await confirmDialog({
      title: `Delete the sheet “${sheet.name}”?`,
      message: nodes ? `Its ${nodes} node${nodes === 1 ? "" : "s"} and their wires go with it.` : undefined,
      confirm: "Delete sheet",
      danger: true,
    });
    if (ok) {
      edit([{ type: "deleteSheet", sheet: sheet.id }]);
    }
  }

  function saveFilter(name: string, expression: string | null) {
    name = name.trim();
    if (!name) return;
    const before = snapshot.filters[name] ?? null;
    if (before === expression) return;
    edit([{ type: "setFilter", name, expression }], [{ type: "setFilter", name, expression: before }],
      expression === null ? "delete filter template" : "filter template");
  }

  // ---------------------------------------------------------------- folders

  type Row = { type: "folder"; path: string; name: string; depth: number } | { type: "sheet"; sheet: Sheet; depth: number };

  let collapsed = $state(new Set<string>());

  /** Folders (from the sheets' folder paths) and sheets, as an indented list; folders first, then sheets. */
  const rows = $derived.by(() => {
    const folders = new Set<string>();
    for (const sheet of snapshot.sheets) {
      const parts = (sheet.folder ?? "").split("/").filter(Boolean);
      for (let i = 1; i <= parts.length; i++) folders.add(parts.slice(0, i).join("/"));
    }
    const out: Row[] = [];
    const add = (parent: string, depth: number) => {
      const children = [...folders]
        .filter((f) => (parent ? f.startsWith(parent + "/") && !f.slice(parent.length + 1).includes("/") : !f.includes("/")))
        .sort((a, b) => a.localeCompare(b));
      for (const folder of children) {
        out.push({ type: "folder", path: folder, name: folder.split("/").pop()!, depth });
        if (!collapsed.has(folder)) add(folder, depth + 1);
      }
      for (const sheet of snapshot.sheets.filter((x) => (x.folder ?? "") === parent)) out.push({ type: "sheet", sheet, depth });
    };
    add("", 0);
    return out;
  });

  /** A folder, or a sheet's folder, is off when it or any folder above it is switched off. */
  function folderOff(path: string): boolean {
    const parts = path.split("/").filter(Boolean);
    for (let i = 1; i <= parts.length; i++) {
      if (snapshot.disabledFolders.includes(parts.slice(0, i).join("/"))) return true;
    }
    return false;
  }

  function toggleCollapsed(path: string) {
    const next = new Set(collapsed);
    next.has(path) ? next.delete(path) : next.add(path);
    collapsed = next;
  }

  function dragDevice(e: DragEvent, key: string) {
    e.dataTransfer?.setData("application/x-reticulum-device", key);
    e.dataTransfer!.effectAllowed = "copy";
  }
</script>

<aside class="flex w-72 shrink-0 flex-col overflow-y-auto border-r border-line bg-panel">
  <section class="p-3">
    <h2 class="panel-title mb-2">Sheets</h2>
    <ul class="flex flex-col gap-0.5">
      {#each rows as row (row.type === "folder" ? `f:${row.path}` : row.sheet.id)}
        {#if row.type === "folder"}
          <li class="flex items-center gap-1.5 rounded-md py-1 pr-2" style="padding-left: {row.depth * 14 + 4}px">
            <button
              class="flex w-4 justify-center muted"
              onclick={() => toggleCollapsed(row.path)}
              title={collapsed.has(row.path) ? "Expand" : "Collapse"}
              aria-label={`${collapsed.has(row.path) ? "Expand" : "Collapse"} ${row.name}`}
              aria-expanded={!collapsed.has(row.path)}
              >{#if collapsed.has(row.path)}<ChevronRight size={14} />{:else}<ChevronDown size={14} />{/if}</button
            >
            <Switch
              hideLabel
              label={`Folder ${row.name} on/off: every sheet in it`}
              checked={!snapshot.disabledFolders.includes(row.path)}
              disabled={!canEdit}
              onchange={(on) =>
                edit(
                  [{ type: "setFolderEnabled", folder: row.path, enabled: on }],
                  [{ type: "setFolderEnabled", folder: row.path, enabled: !snapshot.disabledFolders.includes(row.path) }],
                  "folder on/off",
                )}
            />
            <span class="flex min-w-0 items-center gap-1 truncate font-medium" class:muted={folderOff(row.path)}><Folder size={14} class="shrink-0 muted" /><span class="trim-text truncate">{row.name}</span></span>
          </li>
        {:else}
          {@const sheet = row.sheet}
          <li
            class="group flex items-center gap-2 rounded-md py-1 pr-2"
            style="padding-left: {row.depth * 14 + 8}px; {sheet.id === app.sheetId ? 'background: var(--panel-2)' : ''}"
          >
            <Switch hideLabel label={`Sheet ${sheet.name} on/off`} checked={sheet.enabled !== false} disabled={!canEdit} onchange={(on) => setSheetEnabled(sheet, on)} />
            {#if renaming === sheet.id}
              <TextField class="min-w-0 flex-1" value={sheet.name} required autofocus oncommit={(v) => renameSheet(sheet, v)} onfinish={() => (renaming = null)} />
            {:else}
              <button
                class="flex min-w-0 flex-1 items-center gap-1 text-left"
                class:muted={sheet.enabled === false || folderOff(sheet.folder ?? "") || app.sheetsOff.includes(sheet.id)}
                onclick={() => openSheet(sheet.id)}
                ondblclick={() => canEdit && (renaming = sheet.id)}
                title={sheet.subsheet ? "Sub-sheet: runs only where it is placed. Double-click to rename." : "Double-click to rename"}
              >
                {#if sheet.subsheet}<Layers size={14} class="shrink-0 muted" />{/if}<span class="trim-text truncate">{sheet.name}</span>
                {#if app.sheetsOff.includes(sheet.id)}<span class="shrink-0 text-signal" title="Switched off by redstone"><Zap size={12} /></span>{/if}
                {#if sheet.errors?.length}<span class="shrink-0 text-bad" title={sheet.errors.join("\n")}><TriangleAlert size={12} /></span>{/if}
              </button>
            {/if}
            {#if canEdit}
              <RemoveButton reveal label={`Delete sheet ${sheet.name}`} onclick={() => deleteSheet(sheet)} />
            {/if}
          </li>
        {/if}
      {/each}
    </ul>
    <p class="mt-1 text-xs muted">Put sheets in folders from their settings (the inspector with nothing selected).</p>
    {#if canEdit}
      <form class="mt-2 flex gap-2" onsubmit={(e) => (e.preventDefault(), addSheet())}>
        <input class="flex-1" placeholder="New sheet" bind:value={newSheet} />
        <button class="btn" type="submit">Add</button>
      </form>
      <button class="btn mt-2 text-sm" onclick={importSheet} title="Paste a sheet copied with “Copy as JSON”">Import sheet…</button>
    {/if}
  </section>

  <section class="border-t border-line p-3">
    <h2 class="panel-title mb-2">Devices</h2>
    <div class="mb-2 flex items-center gap-2">
      <input class="min-w-0 flex-1" placeholder="Search devices" aria-label="Search devices" bind:value={deviceSearch} />
      <Segmented
        label="Which devices"
        size="xs"
        value={deviceFilter}
        options={[
          { value: "all", label: `All (${snapshot.devices.length})` },
          { value: "unused", label: `Unused (${unusedCount})`, title: "Devices no sheet uses yet" },
        ]}
        onchange={setDeviceFilter}
      />
    </div>
    <p class="mb-2 text-xs muted">Drag a device onto the canvas to add it; give it inputs and outputs in the inspector.</p>
    <ul class="flex flex-col gap-0.5">
      {#each devices as d (posKey(d.pos))}
        {@const key = posKey(d.pos)}
        <li class="list-row group flex items-center gap-2 rounded-md pr-2" class:active={app.selectedDevice === key}>
          <button
            class="flex min-w-0 flex-1 items-center gap-2 py-1 pl-2 text-left"
            draggable={canEdit && d.state === "active"}
            ondragstart={(e) => dragDevice(e, key)}
            onclick={() => openDevice(d)}
            title={`${d.defaultName}\n${d.block}`}
          >
            <DeviceIcon item={d.icon} />
            <span class="trim-text min-w-0 flex-1 truncate" class:muted={d.state !== "active"}>{deviceName(d)}</span>
            {#if d.state !== "active"}<span class="text-xs text-warn">{d.state}</span>
            {:else if d.outOfRange}<span class="text-xs text-warn" title="Only wireless connectors the antenna doesn't reach reach it">out of range</span>{/if}
          </button>
          <IdentifyBadge device={d} />
        </li>
      {:else}
        <li class="text-sm muted">
          {deviceFilter === "unused" && snapshot.devices.length ? "Every device is used on a sheet." : "No devices. Place a connector next to a machine."}
        </li>
      {/each}
    </ul>
  </section>

  <section class="border-t border-line p-3">
    <h2 class="panel-title mb-2">Filter templates</h2>
    <p class="mb-2 text-xs muted">Use them in any filter as <code>@name</code>.</p>
    <ul class="flex flex-col gap-2">
      {#each Object.entries(snapshot.filters) as [name, expression] (name)}
        <li class="flex flex-col gap-1">
          <div class="flex items-center justify-between">
            <code>@{name}</code>
            {#if canEdit}<RemoveButton label={`Delete template @${name}`} onclick={() => saveFilter(name, null)} />{/if}
          </div>
          <FilterField value={expression} disabled={!canEdit} onchange={(v) => saveFilter(name, v)} />
        </li>
      {/each}
    </ul>
    {#if canEdit}
      <form
        class="mt-2 flex flex-col gap-1"
        onsubmit={(e) => {
          e.preventDefault();
          if (!newFilterName.trim()) return;
          saveFilter(newFilterName, newFilterExpr);
          newFilterName = "";
          newFilterExpr = "";
        }}
      >
        <input placeholder="Template name" aria-label="Template name" bind:value={newFilterName} />
        <FilterField value={newFilterExpr} onchange={(v) => (newFilterExpr = v)} placeholder="e.g. tag:c:ores" />
        <button class="btn self-start" type="submit" disabled={!newFilterName.trim()}>Save template</button>
      </form>
    {/if}
  </section>
</aside>
