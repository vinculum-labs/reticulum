<script lang="ts">
  import { app, edit, rebase } from "../../lib/state.svelte";
  import { PIN_CARRIES } from "../../lib/kinds";
  import { pinName, sheetPins, type GraphNode, type Resource, type Sheet } from "../../lib/types";
  import Select from "../controls/Select.svelte";
  import TextField from "../controls/TextField.svelte";
  import Field from "./Field.svelte";
  import WhileOffField from "./WhileOffField.svelte";

  /**
   * A sheet pin's settings: its name, what it carries and (signal outputs) what it gives while the sheet is off. The
   * same fields wherever a pin is edited (its group in the sheet's panel, or its own panel when picked on the canvas),
   * so the two can't drift apart. A pin just added takes the keyboard in its Name box, to be named straight away.
   */
  let { sheet, pin, disabled = false }: { sheet: Sheet; pin: GraphNode; disabled?: boolean } = $props();

  const canEdit = $derived(!disabled && (app.snapshot?.canEdit ?? false));
  const s = $derived(pin.settings);
  const input = $derived(s.kind === "sheet_input");
  const resource = $derived(s.kind === "sheet_input" || s.kind === "sheet_output" ? s.resource : undefined);
  /** What it is called until named ("in 2"), shown as the Name box's placeholder. */
  const fallback = $derived(pinName({ ...pin, label: undefined, settings: { ...s, name: "" } as GraphNode["settings"] }, sheetPins(sheet, input).findIndex((p) => p.id === pin.id)));

  function save(settings: Partial<GraphNode["settings"]>, label: string) {
    const { after, before } = rebase(pin, { ...pin, label: undefined, settings: { ...pin.settings, ...settings } as GraphNode["settings"] });
    edit([{ type: "updateNode", sheet: sheet.id, node: after }], [{ type: "updateNode", sheet: sheet.id, node: before }], label);
  }
</script>

{#if s.kind === "sheet_input" || s.kind === "sheet_output"}
  <Field label="Name" hint={`Shown by its dot on the sheet's ${input ? "left" : "right"} edge, and on the sheet's node. Drag the pin up or down to reorder.`}>
    <!-- A name once set as the node's label shows here too, and moves into the pin's name when edited. -->
    <TextField
      value={s.name || pin.label}
      disabled={!canEdit}
      placeholder={fallback}
      autofocus={app.nameNode === pin.id}
      oncommit={(name) => save({ name }, "rename pin")}
      onfinish={() => app.nameNode === pin.id && (app.nameNode = null)}
    />
  </Field>
  <Field label="Carries" hint="Wires of other kinds won't connect to it. Anything: whatever is wired to it.">
    <Select value={resource ?? ""} disabled={!canEdit} options={PIN_CARRIES} onchange={(v) => save({ resource: (v || undefined) as Resource | undefined }, "pin type")} />
  </Field>
  {#if s.kind === "sheet_output" && (!resource || resource === "redstone")}
    <WhileOffField value={s.while_off} disabled={!canEdit} onchange={(while_off) => save({ while_off }, "pin while off")} />
  {/if}
{/if}
