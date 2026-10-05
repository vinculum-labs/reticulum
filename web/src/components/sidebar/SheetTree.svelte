<script lang="ts">
  import { KIND_ICON } from "../../lib/icons";
  const BlueprintIcon = KIND_ICON.instance;
  import ChevronDown from "@lucide/svelte/icons/chevron-down";
  import ChevronRight from "@lucide/svelte/icons/chevron-right";
  import Copy from "@lucide/svelte/icons/copy";
  import CopyPlus from "@lucide/svelte/icons/copy-plus";
  import ExternalLink from "@lucide/svelte/icons/external-link";
  import Import from "@lucide/svelte/icons/import";
  import Pencil from "@lucide/svelte/icons/pencil";
  import Plus from "@lucide/svelte/icons/plus";
  import Power from "@lucide/svelte/icons/power";
  import PowerOff from "@lucide/svelte/icons/power-off";
  import Trash from "@lucide/svelte/icons/trash";
  import TriangleAlert from "@lucide/svelte/icons/triangle-alert";
  import { openMenu } from "../../lib/menu.svelte";
  import {
    app,
    copySheetJson,
    createSheet,
    deleteInstance,
    deleteSheet,
    duplicateBlueprint,
    edit,
    freeSpot,
    importSheet,
    moveSheet,
    nextName,
    openInstance,
    openSheet,
    setSheetEnabled,
    updateNodeEdit,
  } from "../../lib/state.svelte";
  import { alarmsIn, instanceStatus, issuesInside, offlineUsed, treeStatus } from "../../lib/status";
  import { childSheets, descendants, offReason, rootSheet } from "../../lib/tree";
  import type { GraphNode, Sheet } from "../../lib/types";
  import AddButton from "../controls/AddButton.svelte";
  import Switch from "../controls/Switch.svelte";
  import TextField from "../controls/TextField.svelte";
  import StatusMark from "../StatusMark.svelte";

  /**
   * The network's sheets as a tree (with the blueprint instances placed on them), then the blueprints. A row opens its
   * sheet; double-click renames, drag moves a sheet inside another, right-click has the rest.
   */

  const snapshot = $derived(app.snapshot!);
  const canEdit = $derived(snapshot.canEdit);
  const power = $derived(app.power ?? snapshot.power);
  const unpowered = $derived(!!power?.enabled && !power.powered);

  /** The sheet, blueprint or instance whose row shows a name box. */
  let renaming = $state<string | null>(null);

  /** Where a new sheet goes: inside the open sheet (the root while a blueprint is open). */
  const newSheetParent = $derived.by(() => {
    const open = snapshot.sheets.find((s) => s.id === app.sheetId);
    return open && !open.blueprint ? open : rootSheet(snapshot) ?? null;
  });

  /**
   * A new sheet inside the open one, its node placed right of what is there. Its row shows (its parent opened in the
   * tree) with its name box focused, to type the name straight away.
   */
  async function addSheet(parent: Sheet | null = newSheetParent) {
    // Every sheet above it opened, so its row (and name box) shows.
    for (let at = parent; at; at = snapshot.sheets.find((s) => s.id === at!.parent) ?? null) {
      if (collapsed.has(at.id)) toggleCollapsed(at.id);
    }
    const id = await createSheet(parent, nextName("Sheet"), freeSpot(parent));
    if (id) renaming = id;
  }

  /** A new blueprint, opened, its row's name box focused. */
  async function addBlueprint() {
    const network = app.current;
    const results = await edit([{ type: "createSheet", name: nextName("Blueprint"), blueprint: true }]);
    if (results?.[0] && app.current === network) {
      openSheet(results[0] as string);
      renaming = results[0] as string;
    }
  }

  function renameSheet(sheet: Sheet, name: string) {
    edit([{ type: "updateSheet", sheet: sheet.id, name }], [{ type: "updateSheet", sheet: sheet.id, name: sheet.name }], "rename sheet");
  }

  /** An instance's own name (its node's label); blank goes back to its blueprint's. */
  function renameInstance(holder: Sheet, node: GraphNode, name: string) {
    updateNodeEdit(holder.id, node, { ...node, label: name || undefined }, "rename");
  }

  /** An instance's own switch (its node's), as one undoable change. */
  function setInstanceEnabled(holder: Sheet, node: GraphNode, on: boolean) {
    updateNodeEdit(holder.id, node, { ...node, enabled: on }, "on/off");
  }

  /** A row of the tree: a sheet, or a blueprint instance placed on one (it runs there, so it shows there). */
  type Row =
    | { kind: "sheet"; sheet: Sheet; depth: number; children: number }
    | { kind: "instance"; holder: Sheet; node: GraphNode; depth: number };

  let collapsed = $state(new Set<string>());

  /** The tree as an indented list: the root, then each sheet followed by the ones inside it, then its instances (by name). */
  const rows = $derived.by(() => {
    const out: Row[] = [];
    const root = rootSheet(snapshot);
    if (!root) return out;
    const add = (sheet: Sheet, depth: number) => {
      const children = childSheets(snapshot, sheet.id);
      const instances = (sheet.nodes ?? []).filter((n) => n.settings.kind === "instance").sort((a, b) => instanceName(a).localeCompare(instanceName(b)));
      out.push({ kind: "sheet", sheet, depth, children: children.length + instances.length });
      if (collapsed.has(sheet.id) || depth >= 32) return;
      for (const child of children) add(child, depth + 1);
      for (const node of instances) out.push({ kind: "instance", holder: sheet, node, depth: depth + 1 });
    };
    add(root, 0);
    return out;
  });

  const blueprints = $derived(snapshot.sheets.filter((s) => s.blueprint).sort((a, b) => a.name.localeCompare(b.name)));

  function blueprintOf(node: GraphNode): Sheet | undefined {
    const id = node.settings.kind === "instance" ? node.settings.sheet : undefined;
    return snapshot.sheets.find((s) => s.id === id);
  }

  /** An instance's name in the tree: its label, else its blueprint's. */
  function instanceName(node: GraphNode): string {
    return node.label || blueprintOf(node)?.name || "Blueprint";
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
    // Checked before the drag is forgotten: canDrop looks the dragged sheet up.
    const ok = canDrop(target);
    dragged = null;
    dropOn = null;
    if (sheet && ok) moveSheet(sheet, target);
  }

  // ---------------------------------------------------------------- right-click menus

  function sheetMenu(e: MouseEvent, sheet: Sheet) {
    openMenu(e, [
      { label: "Open", icon: ExternalLink, run: () => openSheet(sheet.id) },
      canEdit && { label: sheet.root ? "Rename network" : "Rename", icon: Pencil, run: () => (renaming = sheet.id) },
      canEdit && { label: "New sheet inside", icon: Plus, run: () => addSheet(sheet) },
      canEdit && { label: sheet.enabled !== false ? "Turn off" : "Turn on", icon: sheet.enabled !== false ? PowerOff : Power, run: () => setSheetEnabled(sheet, sheet.enabled === false) },
      "-",
      { label: "Copy as JSON", icon: Copy, run: () => copySheetJson(sheet) },
      canEdit && !sheet.root && "-",
      canEdit && !sheet.root && { label: "Delete", icon: Trash, danger: true, shortcut: "Del", run: () => deleteSheet(sheet) },
    ]);
  }

  function instanceMenu(e: MouseEvent, holder: Sheet, node: GraphNode) {
    openMenu(e, [
      { label: "Open", icon: ExternalLink, run: () => openInstance(holder.id, node.id) },
      canEdit && { label: "Rename", icon: Pencil, run: () => (renaming = node.id) },
      canEdit && { label: node.enabled !== false ? "Turn off" : "Turn on", icon: node.enabled !== false ? PowerOff : Power, run: () => setInstanceEnabled(holder, node, node.enabled === false) },
      canEdit && "-",
      canEdit && { label: "Remove", icon: Trash, danger: true, shortcut: "Del", run: () => deleteInstance(holder, node) },
    ]);
  }

  function blueprintMenu(e: MouseEvent, sheet: Sheet) {
    openMenu(e, [
      { label: "Open", icon: ExternalLink, run: () => openSheet(sheet.id) },
      canEdit && { label: "Rename", icon: Pencil, run: () => (renaming = sheet.id) },
      canEdit && { label: "Duplicate", icon: CopyPlus, run: () => duplicateBlueprint(sheet) },
      "-",
      { label: "Copy as JSON", icon: Copy, run: () => copySheetJson(sheet) },
      canEdit && "-",
      canEdit && { label: "Delete", icon: Trash, danger: true, shortcut: "Del", run: () => deleteSheet(sheet) },
    ]);
  }
</script>

<section class="p-3">
  <!-- Padded right like the rows below, so the + ends where their switches do. -->
  <div class="mb-2 flex items-center justify-between pr-2">
    <h2 class="panel-title">Sheets</h2>
    {#if canEdit}
      <div class="flex items-center gap-1">
        <AddButton icon={Import} label="Import sheet: paste one copied with “Copy as JSON”" onclick={() => importSheet("sheet", newSheetParent)} />
        <AddButton label={`New sheet in ${newSheetParent?.name ?? "the network"}`} onclick={() => addSheet()} />
      </div>
    {/if}
  </div>
  <ul class="flex flex-col gap-0.5">
    {#each rows as row (row.kind === "sheet" ? row.sheet.id : row.node.id)}
      {#if row.kind === "instance"}
        {@const holderOff = offReason(snapshot, row.holder, app.sheetsOff)}
        {@const status = instanceStatus(row.node, row.holder, holderOff, snapshot.running, snapshot.enabled, unpowered, !!snapshot.controller, offlineUsed(snapshot, row.holder, row.node.id))}
        <!-- Lit while it is open, or an instance inside it is (opened from within it). -->
        {@const open = app.instance?.node === row.node.id || app.instance?.outer?.[0]?.node === row.node.id}
        {@const name = instanceName(row.node)}
        <!-- An instance: opens its blueprint with its blocks. No chevron (nothing goes inside an instance), not dragged (moved as a node). -->
        <li class="group flex items-center gap-1.5 rounded-md py-1 pr-2" style="padding-left: {row.depth * 8 + 4}px; {open ? 'background: var(--panel-2)' : ''}">
          <span class="w-4 shrink-0"></span>
          <StatusMark {status} />
          {#if renaming === row.node.id}
            <TextField class="min-w-0 flex-1" value={row.node.label} placeholder={blueprintOf(row.node)?.name} autofocus oncommit={(v) => renameInstance(row.holder, row.node, v)} onfinish={() => (renaming = null)} />
          {:else}
            <button
              class="flex min-w-0 flex-1 items-center gap-1 text-left"
              class:muted={status.color === "var(--muted)"}
              onclick={() => openInstance(row.holder.id, row.node.id)}
              oncontextmenu={(e) => instanceMenu(e, row.holder, row.node)}
              onkeydown={(e) => e.key === "Delete" && canEdit && (e.preventDefault(), deleteInstance(row.holder, row.node))}
              ondblclick={() => canEdit && (renaming = row.node.id)}
              title={`The blueprint “${blueprintOf(row.node)?.name ?? "?"}”, placed here: open it to fill its slots. Double-click to rename.`}
            >
              <span class="trim-text truncate">{name}</span>
            </button>
          {/if}
          <Switch
            hideLabel
            small
            label={`${name} (placed blueprint) on/off`}
            checked={row.node.enabled !== false}
            disabled={!canEdit}
            onchange={(on) => setInstanceEnabled(row.holder, row.node, on)}
          />
        </li>
      {:else}
        {@const sheet = row.sheet}
        {@const off = offReason(snapshot, sheet, app.sheetsOff)}
        <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
        <li
          class="group flex items-center gap-1.5 rounded-md py-1 pr-2"
          class:drop-target={dropOn === sheet.id}
          style="padding-left: {row.depth * 8 + 4}px; {sheet.id === app.sheetId && !app.instance ? 'background: var(--panel-2)' : ''}"
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
          <StatusMark status={treeStatus(sheet, off, snapshot.running, snapshot.enabled, unpowered, !!snapshot.controller, offlineUsed(snapshot, sheet), issuesInside(snapshot, sheet), alarmsIn(snapshot, sheet, app.networks.find((n) => n.id === snapshot.id)?.alarms ?? []))} />
          {#if renaming === sheet.id}
            <TextField class="min-w-0 flex-1" value={sheet.name} required autofocus oncommit={(v) => renameSheet(sheet, v)} onfinish={() => (renaming = null)} />
          {:else}
            <button
              class="flex min-w-0 flex-1 items-center gap-1 text-left"
              class:font-medium={sheet.root}
              class:muted={off !== null}
              onclick={() => openSheet(sheet.id)}
              oncontextmenu={(e) => sheetMenu(e, sheet)}
              onkeydown={(e) => e.key === "Delete" && canEdit && !sheet.root && (e.preventDefault(), deleteSheet(sheet))}
              ondblclick={() => canEdit && (renaming = sheet.id)}
              title={sheet.root ? "The network's top sheet. Double-click to rename." : "Double-click to rename — drag onto another sheet to move it inside"}
            >
              <span class="trim-text truncate">{sheet.name}</span>
            </button>
          {/if}
          <!-- At the row's end, so the tree's switches line up in a column whatever the depth. -->
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
    {#if canEdit}
      <div class="flex items-center gap-1">
        <AddButton icon={Import} label="Import blueprint: paste one copied with “Copy as JSON”" onclick={() => importSheet("blueprint")} />
        <AddButton label="New blueprint" onclick={addBlueprint} />
      </div>
    {/if}
  </div>
  <ul class="flex flex-col gap-0.5">
    {#each blueprints as sheet (sheet.id)}
      <!-- Lit while the blueprint itself is open, not while one of its instances is (that one's row in the tree lights). -->
      <li class="group flex items-center gap-1.5 rounded-md py-1 pr-2 pl-2" style={sheet.id === app.sheetId && !app.instance ? "background: var(--panel-2)" : ""}>
        {#if renaming === sheet.id}
          <TextField class="min-w-0 flex-1" value={sheet.name} required autofocus oncommit={(v) => renameSheet(sheet, v)} onfinish={() => (renaming = null)} />
        {:else}
          <button
            class="flex min-w-0 flex-1 items-center gap-1 text-left"
            onclick={() => openSheet(sheet.id)}
            oncontextmenu={(e) => blueprintMenu(e, sheet)}
            onkeydown={(e) => e.key === "Delete" && canEdit && (e.preventDefault(), deleteSheet(sheet))}
            ondblclick={() => canEdit && (renaming = sheet.id)}
            title="Runs only where it's placed. Double-click to rename."
          >
            <BlueprintIcon size={14} class="shrink-0 muted" /><span class="trim-text truncate">{sheet.name}</span>
            {#if sheet.errors?.length}<span class="shrink-0 text-bad" title={sheet.errors.map((e) => e.message).join("\n")}><TriangleAlert size={12} /></span>{/if}
          </button>
        {/if}
      </li>
    {:else}
      <li class="text-xs muted">None yet. A blueprint is a set to build again and again: make one from a sheet (in its settings), then place it on sheets and fill its slots with blocks.</li>
    {/each}
  </ul>
</section>

<style>
  .drop-target {
    outline: 1px dashed var(--accent);
    outline-offset: -1px;
  }
</style>
