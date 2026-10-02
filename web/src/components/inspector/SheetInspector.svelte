<script lang="ts">
  import { app, edit, moveSheet, nodeTitle, openCopy, openSheet, setSheetEnabled, showNode, toast } from "../../lib/state.svelte";
  import { confirmDialog } from "../../lib/dialog.svelte";
  import { copiesOf } from "../../lib/blueprints";
  import { pruneSheet } from "../../lib/prune";
  import Scissors from "@lucide/svelte/icons/scissors";
  import { descendants, sheetPath } from "../../lib/tree";
  import type { Sheet } from "../../lib/types";
  import NumberField from "../controls/NumberField.svelte";
  import Select from "../controls/Select.svelte";
  import Switch from "../controls/Switch.svelte";
  import TextField from "../controls/TextField.svelte";
  import Field from "./Field.svelte";
  import InspectorPanel from "./InspectorPanel.svelte";
  import SheetErrors from "./SheetErrors.svelte";

  let { sheet }: { sheet: Sheet } = $props();

  const snapshot = $derived(app.snapshot!);
  const canEdit = $derived(snapshot.canEdit);
  const parent = $derived(snapshot.sheets.find((s) => s.id === sheet.parent));

  /** Sheets it can move into: in the tree, not itself, not one inside it. */
  const targets = $derived.by(() => {
    const inside = new Set(descendants(snapshot, sheet).map((s) => s.id));
    return snapshot.sheets
      .filter((s) => !s.blueprint && s.id !== sheet.id && !inside.has(s.id))
      .map((s) => ({ value: s.id, label: s.root ? `${s.name} (top)` : sheetPath(snapshot, s) }))
      .sort((a, b) => a.label.localeCompare(b.label));
  });

  async function copyJson() {
    const { errors: _errors, parent: _parent, node: _node, enabled: _enabled, root: _root, ...plain } = sheet;
    try {
      await navigator.clipboard.writeText(JSON.stringify(plain, null, 2));
      toast("Copied. Paste it into “Import sheet…” here or in another world.", "info");
    } catch {
      toast("Couldn't reach the clipboard.");
    }
  }

  function update(patch: Record<string, unknown>, before: Record<string, unknown>, label: string) {
    edit([{ type: "updateSheet", sheet: sheet.id, ...patch }], [{ type: "updateSheet", sheet: sheet.id, ...before }], label);
  }

  const copies = $derived(sheet.blueprint ? copiesOf(snapshot, sheet.id) : []);
  const hasSheets = $derived(descendants(snapshot, sheet).length > 0);

  /**
   * Make blueprint: the sheet's devices become slots, and the sheet stays where it is as the blueprint's first copy
   * (filled with its own blocks). Copy as blueprint ({@code keep}) leaves the sheet as it is.
   */
  async function makeBlueprint(keep: boolean) {
    const network = app.current;
    if (!keep) {
      const ok = await confirmDialog({
        title: `Make a blueprint from “${sheet.name}”?`,
        message:
          "Its devices become slots for their kinds of block, and this sheet becomes the blueprint's first copy, filled with its own blocks: it keeps running as it does now. Then place more copies anywhere and fill their slots. This can't be undone.",
        confirm: "Make blueprint",
      });
      if (!ok || app.current !== network) return;
    }
    const results = await edit([{ type: "makeBlueprint", sheet: sheet.id, ...(keep ? { keep: true } : {}) }]);
    if (results?.[0]) {
      openSheet(results[0] as string);
      toast(keep ? "Blueprint made; the sheet is as it was." : "Blueprint made; the sheet is now its first copy.", "info");
    }
  }

  async function duplicate() {
    const results = await edit([{ type: "duplicateBlueprint", sheet: sheet.id }]);
    if (results?.[0]) openSheet(results[0] as string);
  }

  /** Prune pins for every device node on this sheet. */
  const prunable = $derived(pruneSheet(sheet).count);

  function prune() {
    const { edits, undo } = pruneSheet(sheet);
    if (edits.length) edit(edits, undo, "prune sheet");
  }

  function moveInto(id: string) {
    const target = snapshot.sheets.find((s) => s.id === id);
    if (target) moveSheet(sheet, target);
  }
</script>

<InspectorPanel title={sheet.root ? "Top sheet" : sheet.blueprint ? "Blueprint" : "Sheet"}>
  <Field label="Name">
    <TextField value={sheet.name} required disabled={!canEdit} oncommit={(v) => update({ name: v }, { name: sheet.name }, "rename sheet")} />
  </Field>
  {#if !sheet.blueprint}
    <Switch
      label="On"
      title={sheet.root ? "The network's switch: everything in it" : "Its node's switch: everything in it"}
      checked={sheet.enabled !== false}
      disabled={!canEdit}
      onchange={(on) => setSheetEnabled(sheet, on)}
    />
  {/if}
  {#if parent && sheet.node}
    <Field label="Inside" hint="Its node there has its switch, and a gate: wire a signal into it to switch this sheet with redstone.">
      <div class="flex items-center gap-2 text-sm">
        <span class="min-w-0 flex-1 truncate">{sheetPath(snapshot, parent)}</span>
        <button class="btn shrink-0 text-xs" onclick={() => showNode(parent.id, sheet.node!)}>Show its node</button>
      </div>
    </Field>
    {#if canEdit}
      <Field label="Move into" hint="With everything inside it. Or drag it onto another sheet in the list.">
        <Select value={sheet.parent} options={targets} title="The sheet to move it into" onchange={moveInto} />
      </Field>
    {/if}
  {/if}
  <Field label="Item operations every" hint="Ticks between item operations; sources can override it. Fluids, chemicals and energy move every tick.">
    <NumberField value={sheet.interval ?? 10} min={1} max={72_000} width="md" disabled={!canEdit}
      oncommit={(v) => update({ interval: v }, { interval: sheet.interval ?? 10 }, "sheet interval")} />
  </Field>
  {#if sheet.blueprint}
    <Field label="Copies" hint="Each fills the slots with its own blocks. A change here reaches them all.">
      <ul class="flex flex-col gap-0.5 text-sm">
        {#each copies as c (c.node.id)}
          <li>
            <button class="list-row w-full truncate rounded px-1 text-left text-accent" onclick={() => openCopy(c.sheet.id, c.node.id)}>
              {nodeTitle(c.node)} <span class="muted">in {sheetPath(snapshot, c.sheet)}</span>
            </button>
          </li>
        {:else}
          <li class="muted">None yet: place one with Blueprint in the toolbar of any sheet.</li>
        {/each}
      </ul>
    </Field>
    {#if canEdit}
      <button class="btn self-start text-sm" onclick={duplicate} title="A new blueprint like this one, to change without touching this one's copies">Duplicate blueprint</button>
    {/if}
  {:else if !sheet.root && canEdit}
    <div class="flex flex-col gap-1">
      <div class="flex flex-wrap gap-2">
        <button class="btn text-sm" disabled={hasSheets} onclick={() => makeBlueprint(false)}
          title="Its devices become slots; this sheet stays as the first copy, still running on its blocks">Make blueprint…</button>
        <button class="btn text-sm" disabled={hasSheets} onclick={() => makeBlueprint(true)}
          title="A new blueprint like this sheet; the sheet stays as it is">Copy as blueprint</button>
      </div>
      <span class="text-xs muted">
        {hasSheets ? "Not for a sheet with sheets inside it (yet): move them out first." : "To build the same set again elsewhere: a blueprint is placed as copies, each filled with its own blocks."}
      </span>
    </div>
  {/if}
  {#if canEdit}
    <button
      class="btn flex items-center gap-1 self-start text-sm"
      disabled={prunable === 0}
      title={prunable
        ? `Remove the ${prunable} device input${prunable === 1 ? "" : "s"} and output${prunable === 1 ? "" : "s"} on this sheet nothing is wired to`
        : "Every device input and output on this sheet has a wire"}
      onclick={prune}><Scissors size={14} />Prune pins{prunable ? ` (${prunable})` : ""}</button
    >
  {/if}
  <button class="btn self-start text-sm" onclick={copyJson} title="To paste into “Import sheet…” here or in another world">Copy as JSON</button>
  <SheetErrors sheetId={sheet.id} errors={sheet.errors} />
  <button class="btn self-start text-sm" onclick={() => (app.panel = "network")}>Network settings…</button>
</InspectorPanel>
