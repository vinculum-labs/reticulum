<script lang="ts">
  import ChevronDown from "@lucide/svelte/icons/chevron-down";
  import ChevronRight from "@lucide/svelte/icons/chevron-right";
  import Layers from "@lucide/svelte/icons/layers";
  import Import from "@lucide/svelte/icons/import";
  import TriangleAlert from "@lucide/svelte/icons/triangle-alert";
  import { app, createSheet, deleteCopy, deleteSheet, deviceUses, edit, deviceName, matchesDevice, moveSheet, openCopy, openDevice, openSheet, setSheetEnabled, toast } from "../lib/state.svelte";
  import { childSheets, descendants, offReason, rootSheet } from "../lib/tree";
  import Segmented from "./controls/Segmented.svelte";
  import { confirmDialog, promptDialog } from "../lib/dialog.svelte";
  import { posKey, type GraphNode, type Sheet } from "../lib/types";
  import DeviceIcon from "./DeviceIcon.svelte";
  import IdentifyBadge from "./IdentifyBadge.svelte";
  import FilterField from "./inspector/FilterField.svelte";
  import RemoveButton from "./controls/RemoveButton.svelte";
  import TextField from "./controls/TextField.svelte";
  import Switch from "./controls/Switch.svelte";
  import AddButton from "./controls/AddButton.svelte";
  import { copyStatus, treeStatus } from "../lib/status";

  const snapshot = $derived(app.snapshot!);
  const canEdit = $derived(snapshot.canEdit);
  const power = $derived(app.power ?? snapshot.power);
  const unpowered = $derived(!!power?.enabled && !power.powered);

  let deviceSearch = $state("");
  /** Show every device, or only those no sheet uses yet (remembered per browser). */
  let deviceFilter = $state<"all" | "unused">(storedDeviceFilter());
  const uses = $derived(deviceUses(snapshot));
  /** Devices not placed yet: no device node on any sheet (reading one with a sensor doesn't count). */
  const placed = (key: string) => (uses.get(key) ?? []).some((u) => u.home);
  const unusedCount = $derived(snapshot.devices.filter((d) => !placed(posKey(d.pos))).length);

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
      .filter((d) => matchesDevice(d, deviceSearch) && (deviceFilter === "all" || !placed(posKey(d.pos))))
      .sort((a, b) => deviceName(a).localeCompare(deviceName(b))),
  );

  /** Where a new sheet goes: inside the open sheet (the root while a blueprint is open). */
  const newSheetParent = $derived.by(() => {
    const open = snapshot.sheets.find((s) => s.id === app.sheetId);
    return open && !open.blueprint ? open : rootSheet(snapshot) ?? null;
  });

  /**
   * A new sheet inside the open one, its node placed right of what is there. Its row shows (its parent opened in the
   * tree) with its name box focused, to type the name straight away.
   */
  async function addSheet() {
    const parent = newSheetParent;
    const nodes = parent?.nodes ?? [];
    const right = nodes.length ? Math.max(...nodes.map((n) => n.x ?? 0)) + 260 : 40;
    const top = nodes.length ? Math.min(...nodes.map((n) => n.y ?? 0)) : 40;
    if (parent && collapsed.has(parent.id)) toggleCollapsed(parent.id);
    const id = await createSheet(parent, `Sheet ${snapshot.sheets.length}`, { x: right, y: top });
    if (id) renaming = id;
  }

  /** A new blueprint, opened, its row's name box focused. */
  async function addBlueprint() {
    const results = await edit([{ type: "createSheet", name: `Blueprint ${snapshot.sheets.filter((s) => s.blueprint).length + 1}`, blueprint: true }]);
    if (results?.[0]) {
      openSheet(results[0] as string);
      renaming = results[0] as string;
    }
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
      openSheet(results[0] as string);
      toast("Imported. Devices from another world show as offline until you pick local ones for them.", "info");
    }
  }

  function renameSheet(sheet: Sheet, name: string) {
    edit([{ type: "updateSheet", sheet: sheet.id, name }], [{ type: "updateSheet", sheet: sheet.id, name: sheet.name }], "rename sheet");
  }

  function saveFilter(name: string, expression: string | null) {
    name = name.trim();
    if (!name) return;
    const before = snapshot.filters[name] ?? null;
    if (before === expression) return;
    edit([{ type: "setFilter", name, expression }], [{ type: "setFilter", name, expression: before }],
      expression === null ? "delete filter template" : "filter template");
  }

  // ---------------------------------------------------------------- the sheet tree

  /** A row of the tree: a sheet, or a blueprint copy placed on one (it runs there, so it shows there). */
  type Row =
    | { kind: "sheet"; sheet: Sheet; depth: number; children: number }
    | { kind: "copy"; holder: Sheet; node: GraphNode; depth: number };

  let collapsed = $state(new Set<string>());

  /** The tree as an indented list: the root, then each sheet followed by the ones inside it, then its copies (by name). */
  const rows = $derived.by(() => {
    const out: Row[] = [];
    const root = rootSheet(snapshot);
    if (!root) return out;
    const add = (sheet: Sheet, depth: number) => {
      const children = childSheets(snapshot, sheet.id);
      const copies = (sheet.nodes ?? []).filter((n) => n.settings.kind === "copy").sort((a, b) => copyName(a).localeCompare(copyName(b)));
      out.push({ kind: "sheet", sheet, depth, children: children.length + copies.length });
      if (collapsed.has(sheet.id) || depth >= 32) return;
      for (const child of children) add(child, depth + 1);
      for (const node of copies) out.push({ kind: "copy", holder: sheet, node, depth: depth + 1 });
    };
    add(root, 0);
    return out;
  });

  const blueprints = $derived(snapshot.sheets.filter((s) => s.blueprint).sort((a, b) => a.name.localeCompare(b.name)));

  function blueprintOf(node: GraphNode): Sheet | undefined {
    const id = node.settings.kind === "copy" ? node.settings.sheet : undefined;
    return snapshot.sheets.find((s) => s.id === id);
  }

  /** A copy's name in the tree: its label, else its blueprint's. */
  function copyName(node: GraphNode): string {
    return node.label || blueprintOf(node)?.name || "Copy";
  }

  /** A copy's own name (its node's label); blank goes back to its blueprint's. */
  function renameCopy(holder: Sheet, node: GraphNode, name: string) {
    edit([{ type: "updateNode", sheet: holder.id, node: { ...node, label: name || undefined } }], [{ type: "updateNode", sheet: holder.id, node }], "rename copy");
  }

  /** A copy's own switch (its node's), as one undoable change. */
  function setCopyEnabled(holder: Sheet, node: GraphNode, on: boolean) {
    edit([{ type: "updateNode", sheet: holder.id, node: { ...node, enabled: on } }], [{ type: "updateNode", sheet: holder.id, node }], "copy on/off");
  }

  function toggleCollapsed(id: string) {
    const next = new Set(collapsed);
    next.has(id) ? next.delete(id) : next.add(id);
    collapsed = next;
  }

  // Dragging a sheet onto another moves it inside that one.
  let dragged = $state<string | null>(null);
  let dropOn = $state<string | null>(null);

  function canDrop(target: Sheet): boolean {
    const sheet = snapshot.sheets.find((s) => s.id === dragged);
    return !!sheet && sheet.id !== target.id && sheet.parent !== target.id && !descendants(snapshot, sheet).includes(target);
  }

  function dropSheet(target: Sheet) {
    const sheet = snapshot.sheets.find((s) => s.id === dragged);
    dragged = null;
    dropOn = null;
    if (sheet && canDrop(target)) moveSheet(sheet, target);
  }

  function dragDevice(e: DragEvent, key: string) {
    e.dataTransfer?.setData("application/x-reticulum-device", key);
    e.dataTransfer!.effectAllowed = "copy";
  }
</script>

<aside class="flex w-72 shrink-0 flex-col overflow-y-auto border-r border-line bg-panel">
  <section class="p-3">
    <!-- Padded right like the rows below, so the + ends where their switches do. -->
    <div class="mb-2 flex items-center justify-between pr-2">
      <h2 class="panel-title">Sheets</h2>
      {#if canEdit}
        <div class="flex items-center gap-1">
          <AddButton icon={Import} label="Import sheet: paste one copied with “Copy as JSON”" onclick={importSheet} />
          <AddButton label={`New sheet in ${newSheetParent?.name ?? "the network"}`} onclick={addSheet} />
        </div>
      {/if}
    </div>
    <ul class="flex flex-col gap-0.5">
      {#each rows as row (row.kind === "sheet" ? row.sheet.id : row.node.id)}
        {#if row.kind === "copy"}
          {@const holderOff = offReason(snapshot, row.holder, app.sheetsOff)}
          {@const status = copyStatus(row.node, row.holder, holderOff, snapshot.running, snapshot.enabled, unpowered)}
          {@const open = app.copy?.node === row.node.id}
          {@const name = copyName(row.node)}
          <!-- A copy: opens its blueprint with its blocks. No chevron (nothing goes inside a copy), not dragged (moved as a node). -->
          <li class="group flex items-center gap-1.5 rounded-md py-1 pr-2" style="padding-left: {row.depth * 8 + 4}px; {open ? 'background: var(--panel-2)' : ''}">
            <span class="w-4 shrink-0"></span>
            <span class="status relative flex shrink-0" style="color: {status.color}" title={status.title} role="img" aria-label={status.title}>
              <status.icon size={14} />
              {#each status.badges as badge (badge.corner)}
                <span class="badge {badge.corner}" style="color: {badge.color}"><badge.icon size={8} /></span>
              {/each}
            </span>
            {#if renaming === row.node.id}
              <TextField class="min-w-0 flex-1" value={row.node.label} placeholder={blueprintOf(row.node)?.name} autofocus oncommit={(v) => renameCopy(row.holder, row.node, v)} onfinish={() => (renaming = null)} />
            {:else}
              <button
                class="flex min-w-0 flex-1 items-center gap-1 text-left"
                class:muted={status.color === "var(--muted)"}
                onclick={() => openCopy(row.holder.id, row.node.id)}
                onkeydown={(e) => e.key === "Delete" && canEdit && (e.preventDefault(), deleteCopy(row.holder, row.node))}
                ondblclick={() => canEdit && (renaming = row.node.id)}
                title={`A copy of the blueprint “${blueprintOf(row.node)?.name ?? "?"}”: open it to fill its slots. Double-click to rename.`}
              >
                <span class="trim-text truncate">{name}</span>
              </button>
            {/if}
            <Switch
              hideLabel
              small
              label={`Copy ${name} on/off`}
              checked={row.node.enabled !== false}
              disabled={!canEdit}
              onchange={(on) => setCopyEnabled(row.holder, row.node, on)}
            />
          </li>
        {:else}
        {@const sheet = row.sheet}
        {@const off = offReason(snapshot, sheet, app.sheetsOff)}
        {@const status = treeStatus(sheet, off, snapshot.running, snapshot.enabled, unpowered)}
        <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
        <li
          class="group flex items-center gap-1.5 rounded-md py-1 pr-2"
          class:drop-target={dropOn === sheet.id}
          style="padding-left: {row.depth * 8 + 4}px; {sheet.id === app.sheetId && !app.copy ? 'background: var(--panel-2)' : ''}"
          draggable={canEdit && !sheet.root}
          ondragstart={(e) => {
            dragged = sheet.id;
            e.dataTransfer?.setData("application/x-reticulum-sheet", sheet.id);
            e.dataTransfer!.effectAllowed = "move";
          }}
          ondragend={() => ((dragged = null), (dropOn = null))}
          ondragover={(e) => {
            if (dragged && canDrop(sheet)) {
              e.preventDefault();
              dropOn = sheet.id;
            }
          }}
          ondragleave={() => dropOn === sheet.id && (dropOn = null)}
          ondrop={(e) => {
            e.preventDefault();
            dropSheet(sheet);
          }}
        >
          {#if row.children}
            <button
              class="flex w-4 shrink-0 justify-center muted"
              onclick={() => toggleCollapsed(sheet.id)}
              title={collapsed.has(sheet.id) ? "Expand" : "Collapse"}
              aria-label={`${collapsed.has(sheet.id) ? "Expand" : "Collapse"} ${sheet.name}`}
              aria-expanded={!collapsed.has(sheet.id)}
              >{#if collapsed.has(sheet.id)}<ChevronRight size={14} />{:else}<ChevronDown size={14} />{/if}</button
            >
          {:else}
            <span class="w-4 shrink-0"></span>
          {/if}
          <!-- How it is doing, left of its name: the network's state on the top row; a sheet's problems, why it is off, or running. -->
          <span class="status relative flex shrink-0" style="color: {status.color}" title={status.title} role="img" aria-label={status.title}>
            <status.icon size={14} />
            {#each status.badges as badge (badge.corner)}
              <span class="badge {badge.corner}" style="color: {badge.color}"><badge.icon size={8} /></span>
            {/each}
          </span>
          {#if renaming === sheet.id}
            <TextField class="min-w-0 flex-1" value={sheet.name} required autofocus oncommit={(v) => renameSheet(sheet, v)} onfinish={() => (renaming = null)} />
          {:else}
            <button
              class="flex min-w-0 flex-1 items-center gap-1 text-left"
              class:font-medium={sheet.root}
              class:muted={off !== null}
              onclick={() => openSheet(sheet.id)}
              onkeydown={(e) => e.key === "Delete" && canEdit && !sheet.root && (e.preventDefault(), deleteSheet(sheet))}
              ondblclick={() => canEdit && (renaming = sheet.id)}
              title={sheet.root ? "The network's top sheet. Double-click to rename." : "Double-click to rename — drag onto another sheet to move it inside"}
            >
              <span class="trim-text truncate">{sheet.name}</span>
            </button>
          {/if}
          <!-- At the row's end, so the tree's switches line up in a column whatever the depth. Deleting is in the sheet's panel. -->
          <Switch
            hideLabel
            small
            label={sheet.root ? "The network on/off" : `Sheet ${sheet.name} on/off: everything in it`}
            checked={sheet.enabled !== false}
            disabled={!canEdit}
            onchange={(on) => setSheetEnabled(sheet, on)}
          />
        </li>
        {/if}
      {/each}
    </ul>

    <div class="mt-4 mb-2 flex items-center justify-between pr-2">
      <h2 class="panel-title">Blueprints</h2>
      {#if canEdit}<AddButton label="New blueprint" onclick={addBlueprint} />{/if}
    </div>
    <ul class="flex flex-col gap-0.5">
      {#each blueprints as sheet (sheet.id)}
        <!-- Lit while the blueprint itself is open, not while one of its copies is (that one's row in the tree lights). -->
        <li class="group flex items-center gap-1.5 rounded-md py-1 pr-2 pl-2" style={sheet.id === app.sheetId && !app.copy ? "background: var(--panel-2)" : ""}>
          {#if renaming === sheet.id}
            <TextField class="min-w-0 flex-1" value={sheet.name} required autofocus oncommit={(v) => renameSheet(sheet, v)} onfinish={() => (renaming = null)} />
          {:else}
            <button
              class="flex min-w-0 flex-1 items-center gap-1 text-left"
              onclick={() => openSheet(sheet.id)}
              onkeydown={(e) => e.key === "Delete" && canEdit && (e.preventDefault(), deleteSheet(sheet))}
              ondblclick={() => canEdit && (renaming = sheet.id)}
              title="Runs only as its copies. Double-click to rename."
            >
              <Layers size={14} class="shrink-0 muted" /><span class="trim-text truncate">{sheet.name}</span>
              {#if sheet.errors?.length}<span class="shrink-0 text-bad" title={sheet.errors.map((e) => e.message).join("\n")}><TriangleAlert size={12} /></span>{/if}
            </button>
          {/if}
        </li>
      {:else}
        <li class="text-xs muted">None yet. A blueprint is a set to build again and again: make one from a sheet (in its settings), then place copies of it and fill their slots with blocks.</li>
      {/each}
    </ul>
  </section>

  <section class="border-t border-line p-3">
    <div class="mb-2 flex items-center justify-between gap-2">
      <h2 class="panel-title">Devices</h2>
      <Segmented
        label="Which devices"
        size="xs"
        value={deviceFilter}
        options={[
          { value: "all", label: `All (${snapshot.devices.length})` },
          { value: "unused", label: `Unused (${unusedCount})`, title: "Devices not placed on any sheet yet" },
        ]}
        onchange={setDeviceFilter}
      />
    </div>
    <input class="mb-2 w-full" placeholder="Search devices" aria-label="Search devices" bind:value={deviceSearch} />
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

<style>
  /* A status mark on a corner of a tree row's icon, on the sidebar's color so it reads over the icon's lines. */
  .status .badge {
    position: absolute;
    right: -4px;
    display: flex;
    padding: 1px;
    border-radius: 50%;
    background: var(--panel);
  }
  .status .badge.top {
    top: -4px;
  }
  .status .badge.bottom {
    bottom: -4px;
  }
  .drop-target {
    outline: 1px dashed var(--accent);
    outline-offset: -1px;
  }
</style>
