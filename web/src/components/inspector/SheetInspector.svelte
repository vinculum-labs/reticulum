<script lang="ts">
  import { app, edit, moveSheet, setSheetEnabled, showNode, toast } from "../../lib/state.svelte";
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
  {#if !sheet.root}
    <label class="flex items-start gap-2 text-sm">
      <input
        type="checkbox"
        checked={sheet.blueprint === true}
        disabled={!canEdit}
        onchange={(e) => update({ blueprint: e.currentTarget.checked }, { blueprint: sheet.blueprint === true }, "blueprint")}
      />
      <span>
        Use as blueprint
        <span class="block text-xs muted">
          It leaves the tree and stops running on its own; other sheets place copies of it as a node. A sheet with sheets inside it can't be one.
        </span>
      </span>
    </label>
  {/if}
  <button class="btn self-start text-sm" onclick={copyJson} title="To paste into “Import sheet…” here or in another world">Copy as JSON</button>
  <SheetErrors sheetId={sheet.id} errors={sheet.errors} />
  <button class="btn self-start text-sm" onclick={() => (app.panel = "network")}>Network settings…</button>
</InspectorPanel>
