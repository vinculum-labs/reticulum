<script lang="ts">
  import { app, edit, deviceName } from "../lib/state.svelte";
  import { posKey, type Sheet } from "../lib/types";
  import DeviceIcon from "./DeviceIcon.svelte";
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

  function dragDevice(e: DragEvent, key: string) {
    e.dataTransfer?.setData("application/x-reticulum-device", key);
    e.dataTransfer!.effectAllowed = "copy";
  }
</script>

<aside class="flex w-72 shrink-0 flex-col overflow-y-auto border-r" style="background: var(--panel); border-color: var(--line)">
  <section class="p-3">
    <h2 class="panel-title mb-2">Sheets</h2>
    <ul class="flex flex-col gap-1">
      {#each snapshot.sheets as sheet (sheet.id)}
        <li
          class="group flex items-center gap-2 rounded-md px-2 py-1"
          style={sheet.id === app.sheetId ? "background: var(--panel-2)" : ""}
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
                if (e.key === "Escape") renaming = null;
              }}
            />
          {:else}
            <button
              class="flex-1 truncate text-left"
              style={sheet.enabled === false ? "color: var(--muted)" : ""}
              onclick={() => {
                app.sheetId = sheet.id;
                app.selectedNodes = [];
                app.selectedEdges = [];
                app.selectedDevice = null;
              }}
              ondblclick={() => canEdit && (renaming = sheet.id)}
              title="Double-click to rename"
            >
              {sheet.name}
              {#if sheet.errors?.length}<span style="color: var(--bad)" title={sheet.errors.join("\n")}> ⚠</span>{/if}
            </button>
          {/if}
          {#if canEdit}
            <button class="invisible text-sm muted group-hover:visible" onclick={() => deleteSheet(sheet)} title="Delete sheet">✕</button>
          {/if}
        </li>
      {/each}
    </ul>
    {#if canEdit}
      <form class="mt-2 flex gap-2" onsubmit={(e) => (e.preventDefault(), addSheet())}>
        <input class="flex-1" placeholder="New sheet" bind:value={newSheet} />
        <button class="btn" type="submit">Add</button>
      </form>
    {/if}
  </section>

  <section class="border-t p-3" style="border-color: var(--line)">
    <h2 class="panel-title mb-2">Devices</h2>
    <input class="mb-2 w-full" placeholder="Search devices" bind:value={deviceSearch} />
    <p class="mb-2 text-xs muted">Drag a device onto the canvas to add it as a source.</p>
    <ul class="flex flex-col gap-0.5">
      {#each devices as d (posKey(d.pos))}
        {@const key = posKey(d.pos)}
        <li>
          <button
            class="flex w-full items-center gap-2 rounded-md px-2 py-1 text-left"
            style={app.selectedDevice === key ? "background: var(--panel-2)" : ""}
            draggable={canEdit && d.state === "active"}
            ondragstart={(e) => dragDevice(e, key)}
            onclick={() => {
              app.selectedDevice = key;
              app.selectedNodes = [];
              app.selectedEdges = [];
            }}
            title={`${d.defaultName}\n${d.block}`}
          >
            <DeviceIcon item={d.icon} />
            <span class="min-w-0 flex-1 truncate" style={d.state !== "active" ? "color: var(--muted)" : ""}>{deviceName(d)}</span>
            {#if d.state !== "active"}<span class="text-xs" style="color: var(--warn)">{d.state}</span>{/if}
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
