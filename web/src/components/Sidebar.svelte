<script lang="ts">
  import ChevronDown from "@lucide/svelte/icons/chevron-down";
  import ChevronRight from "@lucide/svelte/icons/chevron-right";
  import Layers from "@lucide/svelte/icons/layers";
  import Import from "@lucide/svelte/icons/import";
  import Funnel from "@lucide/svelte/icons/funnel";
  import ExternalLink from "@lucide/svelte/icons/external-link";
  import Pencil from "@lucide/svelte/icons/pencil";
  import Plus from "@lucide/svelte/icons/plus";
  import Copy from "@lucide/svelte/icons/copy";
  import CopyPlus from "@lucide/svelte/icons/copy-plus";
  import Trash from "@lucide/svelte/icons/trash";
  import Lightbulb from "@lucide/svelte/icons/lightbulb";
  import { openMenu } from "../lib/menu.svelte";
  import Share from "@lucide/svelte/icons/share";
  import TriangleAlert from "@lucide/svelte/icons/triangle-alert";
  import { app, copySheetJson, createSheet, deleteCopy, deleteSheet, deviceUses, duplicateBlueprint, edit, isIdentified, toggleIdentify, deviceName, matchesDevice, moveSheet, openCopy, openDevice, openSheet, setSheetEnabled, toast } from "../lib/state.svelte";
  import { childSheets, descendants, offReason, rootSheet } from "../lib/tree";
  import Segmented from "./controls/Segmented.svelte";
  import { confirmDialog, promptDialog } from "../lib/dialog.svelte";
  import { posKey, type Device, type GraphNode, type Sheet } from "../lib/types";
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
  /** The filter template open for editing (its filter shown under its row), and the one being renamed. */
  let openTemplate = $state<string | null>(null);
  let renamingTemplate = $state<string | null>(null);

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
  async function addSheet(parent: Sheet | null = newSheetParent) {
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
  /**
   * Pastes a sheet or blueprint copied with "Copy as JSON" (from this or another world). A blueprint lands among the
   * blueprints wherever it is pasted; the Blueprints import takes only blueprints (a sheet's devices aren't slots).
   */
  async function importSheet(what: "sheet" | "blueprint") {
    const text = await promptDialog({
      title: what === "blueprint" ? "Import blueprint" : "Import sheet",
      message: `Paste a ${what} copied with “Copy as JSON”, from this world or another.`,
      confirm: "Import",
      multiline: true,
    });
    if (!text?.trim()) return;
    let sheet: { blueprint?: boolean; name?: string } | null = null;
    try {
      sheet = JSON.parse(text);
    } catch {
      // not JSON: said below
    }
    if (!sheet || typeof sheet !== "object") {
      toast(`That isn't a copied ${what} (not JSON).`);
      return;
    }
    if (what === "blueprint" && !sheet.blueprint) {
      toast("That's a sheet, not a blueprint — import it with the Sheets import (or make a blueprint from it once it's in).");
      return;
    }
    // A name already taken here gets marked, so the import can be told from what it came from.
    if (typeof sheet.name === "string" && snapshot.sheets.some((s) => s.name === sheet!.name)) sheet = { ...sheet, name: `${sheet.name} (imported)` };
    const results = await edit([{ type: "importSheet", sheet }]);
    if (!results?.[0]) return;
    openSheet(results[0] as string);
    if (sheet.blueprint) {
      toast(
        what === "blueprint" ? "Imported. Its copies fill its slots with blocks of the same kinds." : "That's a blueprint — it's been added to Blueprints.",
        "info",
      );
    } else {
      toast("Imported. Devices from another world show as offline until you pick local ones for them.", "info");
    }
  }

  function renameSheet(sheet: Sheet, name: string) {
    edit([{ type: "updateSheet", sheet: sheet.id, name }], [{ type: "updateSheet", sheet: sheet.id, name: sheet.name }], "rename sheet");
  }

  /** A new template under the first free name ("template", "template2", ...), open with its name box focused. */
  async function addTemplate() {
    let name = "template";
    for (let i = 2; name in snapshot.filters; i++) name = `template${i}`;
    const ok = await edit([{ type: "setFilter", name, expression: "" }], [{ type: "setFilter", name, expression: null }], "add filter template");
    if (ok) {
      openTemplate = name;
      renamingTemplate = name;
    }
  }

  /** Copies every filter template as JSON, to paste with the templates' import here or in another world. */
  async function exportTemplates() {
    try {
      await navigator.clipboard.writeText(JSON.stringify({ filterTemplates: snapshot.filters }, null, 2));
      const n = Object.keys(snapshot.filters).length;
      toast(`Copied ${n} template${n === 1 ? "" : "s"}. Paste them with the templates' import, here or in another world.`, "info");
    } catch {
      toast("Couldn't reach the clipboard.");
    }
  }

  /**
   * Pastes templates copied with the export (or a plain name → filter map). New names are added; names already here
   * with another filter are replaced only when asked. Sent so that a template comes after any it uses (@other), as
   * the server checks each one as it is saved. One undoable change.
   */
  async function importTemplates() {
    const text = await promptDialog({
      title: "Import filter templates",
      message: "Paste templates copied with the templates' export, from this world or another.",
      confirm: "Import",
      multiline: true,
    });
    if (!text?.trim()) return;
    let parsed: unknown;
    try {
      parsed = JSON.parse(text);
    } catch {
      parsed = null;
    }
    const map = (parsed && typeof parsed === "object" && "filterTemplates" in parsed ? (parsed as { filterTemplates: unknown }).filterTemplates : parsed) as Record<string, unknown> | null;
    const incoming = map && typeof map === "object" ? Object.entries(map).filter((e): e is [string, string] => typeof e[1] === "string") : [];
    if (!incoming.length) {
      toast("That isn't copied filter templates.");
      return;
    }
    const clashing = incoming.filter(([name, expression]) => name in snapshot.filters && snapshot.filters[name] !== expression);
    const same = incoming.filter(([name, expression]) => snapshot.filters[name] === expression);
    if (clashing.length) {
      const ok = await confirmDialog({
        title: `Replace ${clashing.length} template${clashing.length === 1 ? "" : "s"}?`,
        message: `${clashing.map(([n]) => "@" + n).join(", ")} already ${clashing.length === 1 ? "exists" : "exist"} here with another filter. Replace ${clashing.length === 1 ? "it" : "them"} with the imported ${clashing.length === 1 ? "one" : "ones"}? Cancel imports nothing.`,
        confirm: "Replace",
      });
      if (!ok) return;
    }
    const todo = incoming.filter(([name]) => !same.some(([s]) => s === name));
    if (!todo.length) {
      toast("Those templates are all here already.", "info");
      return;
    }
    // Those a template uses first: each in turn once every @name in it is here or already sent.
    const ordered: [string, string][] = [];
    const known = new Set(Object.keys(snapshot.filters).filter((n) => !todo.some(([t]) => t === n)));
    let left = todo;
    while (left.length) {
      const ready = left.filter(([, expression]) => [...expression.matchAll(/@([A-Za-z0-9_\-.]+)/g)].every((m) => known.has(m[1]) || !left.some(([n]) => n === m[1])));
      const batch = ready.length ? ready : left; // a loop between them: let the server say so
      for (const entry of batch) {
        ordered.push(entry);
        known.add(entry[0]);
      }
      left = left.filter((e) => !batch.includes(e));
    }
    const ok = await edit(
      ordered.map(([name, expression]) => ({ type: "setFilter", name, expression })),
      ordered.map(([name]) => ({ type: "setFilter", name, expression: snapshot.filters[name] ?? null })).reverse(),
      "import filter templates",
    );
    if (ok) toast(`Imported ${ordered.length} template${ordered.length === 1 ? "" : "s"}.`, "info");
  }

  /** Renames a template, and everywhere it is used: other templates and every filter on every sheet. One undo. */
  function renameTemplate(from: string, to: string) {
    to = to.trim().replace(/^@/, "");
    if (!to || to === from) return;
    if (to in snapshot.filters) {
      toast(`There's already a template @${to}.`);
      return;
    }
    edit([{ type: "renameFilter", from, to }], [{ type: "renameFilter", from: to, to: from }], "rename filter template");
    if (openTemplate === from) openTemplate = to;
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

  // ---------------------------------------------------------------- right-click menus

  function sheetMenu(e: MouseEvent, sheet: Sheet) {
    openMenu(e, [
      { label: "Open", icon: ExternalLink, run: () => openSheet(sheet.id) },
      canEdit && { label: "Rename", icon: Pencil, hint: sheet.root ? "Renames the network too" : undefined, run: () => (renaming = sheet.id) },
      canEdit && { label: "New sheet inside", icon: Plus, run: () => addSheet(sheet) },
      canEdit && { label: "On", checked: sheet.enabled !== false, hint: sheet.root ? "The network's switch" : undefined, run: () => setSheetEnabled(sheet, sheet.enabled === false) },
      "-",
      { label: "Copy as JSON", icon: Copy, run: () => copySheetJson(sheet) },
      canEdit && !sheet.root && "-",
      canEdit && !sheet.root && { label: "Delete", icon: Trash, danger: true, shortcut: "Del", run: () => deleteSheet(sheet) },
    ]);
  }

  function copyMenu(e: MouseEvent, holder: Sheet, node: GraphNode) {
    openMenu(e, [
      { label: "Open", icon: ExternalLink, hint: "The blueprint with this copy's blocks", run: () => openCopy(holder.id, node.id) },
      canEdit && { label: "Rename", icon: Pencil, run: () => (renaming = node.id) },
      canEdit && { label: "On", checked: node.enabled !== false, run: () => setCopyEnabled(holder, node, node.enabled === false) },
      canEdit && "-",
      canEdit && { label: "Delete", icon: Trash, danger: true, shortcut: "Del", run: () => deleteCopy(holder, node) },
    ]);
  }

  function blueprintMenu(e: MouseEvent, sheet: Sheet) {
    openMenu(e, [
      { label: "Open", icon: ExternalLink, run: () => openSheet(sheet.id) },
      canEdit && { label: "Rename", icon: Pencil, run: () => (renaming = sheet.id) },
      canEdit && { label: "Duplicate", icon: CopyPlus, hint: "A new blueprint like this one", run: () => duplicateBlueprint(sheet) },
      "-",
      { label: "Copy as JSON", icon: Copy, run: () => copySheetJson(sheet) },
      canEdit && "-",
      canEdit && { label: "Delete", icon: Trash, danger: true, shortcut: "Del", run: () => deleteSheet(sheet) },
    ]);
  }

  function deviceMenu(e: MouseEvent, d: Device) {
    const open = snapshot.sheets.find((s) => s.id === app.sheetId);
    const homed = (deviceUses(snapshot).get(posKey(d.pos)) ?? []).some((u) => u.home);
    const canPlace = canEdit && d.state === "active" && !!open && !app.copy && (open.blueprint || !homed);
    openMenu(e, [
      { label: "Identify", icon: Lightbulb, hint: "Light it up in the world", checked: isIdentified({ kind: "device", device: d.pos }), run: () => toggleIdentify({ kind: "device", device: d.pos }) },
      { label: "Names…", icon: Pencil, hint: "Its name and its faces'", run: () => openDevice(d) },
      canEdit && {
        label: open?.blueprint ? "Add a slot to the open blueprint" : "Add to the open sheet",
        icon: Plus,
        disabled: !canPlace,
        hint: !canPlace && homed && !open?.blueprint ? "It already has a node" : undefined,
        run: () => window.dispatchEvent(new CustomEvent("reticulum:place-device", { detail: posKey(d.pos) })),
      },
    ]);
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
          <AddButton icon={Import} label="Import sheet: paste one copied with “Copy as JSON”" onclick={() => importSheet("sheet")} />
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
                oncontextmenu={(e) => copyMenu(e, row.holder, row.node)}
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
              oncontextmenu={(e) => sheetMenu(e, sheet)}
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
      {#if canEdit}
        <div class="flex items-center gap-1">
          <AddButton icon={Import} label="Import blueprint: paste one copied with “Copy as JSON”" onclick={() => importSheet("blueprint")} />
          <AddButton label="New blueprint" onclick={addBlueprint} />
        </div>
      {/if}
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
              oncontextmenu={(e) => blueprintMenu(e, sheet)}
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
            oncontextmenu={(e) => deviceMenu(e, d)}
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
    <!-- As Sheets and Blueprints: a + on the heading, a row each; a row opens to edit its filter. -->
    <div class="mb-2 flex items-center justify-between pr-2">
      <h2 class="panel-title">Filter templates</h2>
      <div class="flex items-center gap-1">
        {#if Object.keys(snapshot.filters).length}<AddButton icon={Share} label="Export templates: copy them all as JSON" onclick={exportTemplates} />{/if}
        {#if canEdit}
          <AddButton icon={Import} label="Import templates: paste ones copied with the export" onclick={importTemplates} />
          <AddButton label="New filter template" onclick={addTemplate} />
        {/if}
      </div>
    </div>
    <ul class="flex flex-col gap-0.5">
      {#each Object.entries(snapshot.filters) as [name, expression] (name)}
        {@const open = openTemplate === name}
        <li class="flex flex-col rounded-md" style={open ? "background: var(--panel-2)" : ""}>
          <div class="flex items-center gap-1.5 py-1 pr-2 pl-2">
            <Funnel size={14} class="shrink-0 muted" />
            {#if renamingTemplate === name}
              <TextField class="min-w-0 flex-1 font-mono" value={name} required autofocus oncommit={(v) => renameTemplate(name, v)} onfinish={() => (renamingTemplate = null)} />
            {:else}
              <button
                class="flex min-w-0 flex-1 items-baseline gap-2 text-left"
                onclick={() => (openTemplate = open ? null : name)}
                ondblclick={() => canEdit && (renamingTemplate = name)}
                onkeydown={(e) => e.key === "Delete" && canEdit && (e.preventDefault(), saveFilter(name, null))}
                title={`Use it in any filter as @${name}. Click to edit its filter, double-click to rename.`}
              >
                <code class="trim-text shrink-0">@{name}</code>
                <!-- Its filter at a glance; open, the box below shows it. -->
                {#if !open}<span class="trim-text min-w-0 truncate font-mono text-xs muted">{expression || "everything"}</span>{/if}
              </button>
            {/if}
            {#if open && canEdit}<RemoveButton label={`Delete template @${name}`} onclick={() => saveFilter(name, null)} />{/if}
          </div>
          {#if open}
            <div class="px-2 pb-2">
              <FilterField value={expression} disabled={!canEdit} placeholder="everything" onchange={(v) => saveFilter(name, v)} />
            </div>
          {/if}
        </li>
      {:else}
        <li class="text-xs muted">None yet. A template is a named filter: use it in any filter as <code>@name</code>.</li>
      {/each}
    </ul>
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
