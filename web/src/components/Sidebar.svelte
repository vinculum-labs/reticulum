<script lang="ts">
  import { app, edit, deviceName, openDevice, toast } from "../lib/state.svelte";
  import { posKey, type Sheet } from "../lib/types";
  import DeviceIcon from "./DeviceIcon.svelte";
  import IdentifyBadge from "./IdentifyBadge.svelte";
  import FilterField from "./inspector/FilterField.svelte";

  const snapshot = $derived(app.snapshot!);
  const canEdit = $derived(snapshot.canEdit);

  let newSheet = $state("");
  let deviceSearch = $state("");
  let renaming = $state<string | null>(null);
  let newFilterName = $state("");
  let newFilterExpr = $state("");

  const devices = $derived(
    snapshot.devices
      .filter((d) => {
        const q = deviceSearch.trim().toLowerCase();
        return !q || deviceName(d).toLowerCase().includes(q) || d.block.includes(q);
      })
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
    const text = prompt("Paste a sheet copied with “Copy as JSON”:");
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
    renaming = null;
    name = name.trim();
    if (name && name !== sheet.name) {
      edit([{ type: "updateSheet", sheet: sheet.id, name }], [{ type: "updateSheet", sheet: sheet.id, name: sheet.name }], "rename sheet");
    }
  }

  function deleteSheet(sheet: Sheet) {
    if (confirm(`Delete sheet "${sheet.name}" and its ${sheet.nodes?.length ?? 0} nodes?`)) {
      edit([{ type: "deleteSheet", sheet: sheet.id }]);
    }
  }

  function saveFilter(name: string, expression: string | null) {
    name = name.trim();
    if (!name) return;
    edit([{ type: "setFilter", name, expression }]);
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

<aside class="flex w-72 shrink-0 flex-col overflow-y-auto border-r" style="background: var(--panel); border-color: var(--line)">
  <section class="p-3">
    <h2 class="panel-title mb-2">Sheets</h2>
    <ul class="flex flex-col gap-0.5">
      {#each rows as row (row.type === "folder" ? `f:${row.path}` : row.sheet.id)}
        {#if row.type === "folder"}
          <li class="flex items-center gap-1.5 rounded-md py-1 pr-2" style="padding-left: {row.depth * 14 + 4}px">
            <button class="w-4 text-xs muted" onclick={() => toggleCollapsed(row.path)} title={collapsed.has(row.path) ? "Expand" : "Collapse"}
              >{collapsed.has(row.path) ? "▸" : "▾"}</button
            >
            <input
              type="checkbox"
              checked={!snapshot.disabledFolders.includes(row.path)}
              disabled={!canEdit}
              title="Folder on/off: every sheet in it"
              onchange={(e) =>
                edit(
                  [{ type: "setFolderEnabled", folder: row.path, enabled: e.currentTarget.checked }],
                  [{ type: "setFolderEnabled", folder: row.path, enabled: !snapshot.disabledFolders.includes(row.path) }],
                  "folder on/off",
                )}
            />
            <span class="truncate font-medium" style={folderOff(row.path) ? "color: var(--muted)" : ""}>📁 {row.name}</span>
          </li>
        {:else}
          {@const sheet = row.sheet}
          <li
            class="group flex items-center gap-2 rounded-md py-1 pr-2"
            style="padding-left: {row.depth * 14 + 8}px; {sheet.id === app.sheetId ? 'background: var(--panel-2)' : ''}"
          >
            <input
              type="checkbox"
              checked={sheet.enabled !== false}
              disabled={!canEdit}
              title="Sheet on/off"
              onchange={(e) =>
                edit(
                  [{ type: "updateSheet", sheet: sheet.id, enabled: e.currentTarget.checked }],
                  [{ type: "updateSheet", sheet: sheet.id, enabled: sheet.enabled !== false }],
                  "sheet on/off",
                )}
            />
            {#if renaming === sheet.id}
              <!-- svelte-ignore a11y_autofocus -->
              <input
                class="flex-1"
                value={sheet.name}
                autofocus
                onblur={(e) => renameSheet(sheet, e.currentTarget.value)}
                onkeydown={(e) => {
                  if (e.key === "Enter") renameSheet(sheet, e.currentTarget.value);
                  if (e.key === "Escape") {
                    e.preventDefault();
                    renaming = null;
                  }
                }}
              />
            {:else}
              <button
                class="flex-1 truncate text-left"
                style={sheet.enabled === false || folderOff(sheet.folder ?? "") || app.sheetsOff.includes(sheet.id) ? "color: var(--muted)" : ""}
                onclick={() => {
                  app.sheetId = sheet.id;
                  app.selectedNodes = [];
                  app.selectedEdges = [];
                  app.selectedDevice = null;
                }}
                ondblclick={() => canEdit && (renaming = sheet.id)}
                title={sheet.subsheet ? "Sub-sheet: runs only where it is placed. Double-click to rename." : "Double-click to rename"}
              >
                {#if sheet.subsheet}<span class="muted">⧉ </span>{/if}{sheet.name}
                {#if app.sheetsOff.includes(sheet.id)}<span style="color: var(--c-signal)" title="Switched off by redstone"> ⚡</span>{/if}
                {#if sheet.errors?.length}<span style="color: var(--bad)" title={sheet.errors.join("\n")}> ⚠</span>{/if}
              </button>
            {/if}
            {#if canEdit}
              <button class="invisible text-sm muted group-hover:visible" onclick={() => deleteSheet(sheet)} title="Delete sheet">✕</button>
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

  <section class="border-t p-3" style="border-color: var(--line)">
    <h2 class="panel-title mb-2">Devices</h2>
    <input class="mb-2 w-full" placeholder="Search devices" bind:value={deviceSearch} />
    <p class="mb-2 text-xs muted">Drag a device onto the canvas to add it; give it inputs and outputs in the inspector.</p>
    <ul class="flex flex-col gap-0.5">
      {#each devices as d (posKey(d.pos))}
        {@const key = posKey(d.pos)}
        <li>
          <button
            class="group flex w-full items-center gap-2 rounded-md px-2 py-1 text-left"
            style={app.selectedDevice === key ? "background: var(--panel-2)" : ""}
            draggable={canEdit && d.state === "active"}
            ondragstart={(e) => dragDevice(e, key)}
            onclick={() => openDevice(d)}
            title={`${d.defaultName}\n${d.block}`}
          >
            <DeviceIcon item={d.icon} />
            <span class="min-w-0 flex-1 truncate" style={d.state !== "active" ? "color: var(--muted)" : ""}>{deviceName(d)}</span>
            <IdentifyBadge device={d} />
            {#if d.state !== "active"}<span class="text-xs" style="color: var(--warn)">{d.state}</span>
            {:else if d.outOfRange}<span class="text-xs" style="color: var(--warn)" title="Only wireless connectors the antenna doesn't reach reach it">out of range</span>{/if}
          </button>
        </li>
      {:else}
        <li class="text-sm muted">No devices. Place a connector next to a machine.</li>
      {/each}
    </ul>
  </section>

  <section class="border-t p-3" style="border-color: var(--line)">
    <h2 class="panel-title mb-2">Filter templates</h2>
    <p class="mb-2 text-xs muted">Use them in any filter as <code>@name</code>.</p>
    <ul class="flex flex-col gap-2">
      {#each Object.entries(snapshot.filters) as [name, expression] (name)}
        <li class="flex flex-col gap-1">
          <div class="flex items-center justify-between">
            <code>@{name}</code>
            {#if canEdit}<button class="text-sm muted" onclick={() => saveFilter(name, null)} title="Delete template">✕</button>{/if}
          </div>
          <FilterField value={expression} disabled={!canEdit} onchange={(v) => saveFilter(name, v)} />
        </li>
      {/each}
    </ul>
    {#if canEdit}
      <div class="mt-2 flex flex-col gap-1">
        <input placeholder="Template name" bind:value={newFilterName} />
        <FilterField value={newFilterExpr} onchange={(v) => (newFilterExpr = v)} placeholder="e.g. tag:c:ores" />
        <button
          class="btn self-start"
          disabled={!newFilterName.trim()}
          onclick={() => {
            saveFilter(newFilterName, newFilterExpr);
            newFilterName = "";
            newFilterExpr = "";
          }}>Save template</button
        >
      </div>
    {/if}
  </section>
</aside>
