<script lang="ts">
  import { addSheetPin, app, edit, rebase, showNode } from "../../lib/state.svelte";
  import { PIN_CARRIES } from "../../lib/kinds";
  import { pinName, sheetPins, type Edit, type GraphNode, type Resource, type Sheet } from "../../lib/types";
  import PinMark from "../controls/PinMark.svelte";
  import RemoveButton from "../controls/RemoveButton.svelte";
  import Select from "../controls/Select.svelte";
  import TextField from "../controls/TextField.svelte";

  /**
   * A sheet's pins, inputs and outputs, in the order its node shows them: rename them, set what they carry, add and
   * remove them. Removing one takes its wires inside along (undo brings both back); wires on the sheet's node stay,
   * and come back to the pin with it.
   */
  let { sheet }: { sheet: Sheet } = $props();

  const canEdit = $derived(app.snapshot?.canEdit ?? false);

  function save(pin: GraphNode, settings: Partial<GraphNode["settings"]>, label: string) {
    const { after, before } = rebase(pin, { ...pin, label: undefined, settings: { ...pin.settings, ...settings } as GraphNode["settings"] });
    edit([{ type: "updateNode", sheet: sheet.id, node: after }], [{ type: "updateNode", sheet: sheet.id, node: before }], label);
  }

  function remove(pin: GraphNode) {
    const inner = (sheet.wires ?? []).filter((w) => w.from === pin.id || w.to === pin.id);
    edit(
      [{ type: "removeNode", sheet: sheet.id, node: pin.id }],
      [{ type: "addNode", sheet: sheet.id, node: pin } as Edit, ...inner.map((wire) => ({ type: "connect", sheet: sheet.id, wire }) as Edit)],
      "remove pin",
    );
  }
</script>

{#each [true, false] as input (input)}
  {@const pins = sheetPins(sheet, input)}
  <section class="flex flex-col gap-1">
    <div class="flex items-center justify-between">
      <h3 class="panel-title flex items-center gap-1.5"><PinMark solid={input} />{input ? "Input pins (left)" : "Output pins (right)"}</h3>
      {#if canEdit}
        <button class="btn flex items-center gap-1 text-xs" onclick={() => addSheetPin(sheet, input)}>
          <PinMark solid={input} />Add
        </button>
      {/if}
    </div>
    {#each pins as pin, i (pin.id)}
      {@const s = pin.settings}
      <div class="group grid grid-cols-[minmax(0,1fr)_7.5rem_auto] items-center gap-1">
        <TextField class="w-full min-w-0" value={pinName(pin, i)} disabled={!canEdit} title="The pin's name"
          oncommit={(name) => save(pin, { name }, "rename pin")} />
        <Select
          value={s.kind === "sheet_input" || s.kind === "sheet_output" ? (s.resource ?? "") : ""}
          disabled={!canEdit}
          title="What it carries"
          options={PIN_CARRIES}
          onchange={(v) => save(pin, { resource: (v || undefined) as Resource | undefined }, "pin type")}
        />
        <div class="flex items-center">
          <button class="btn px-1 text-xs" title="Show it on the canvas" onclick={() => showNode(sheet.id, pin.id)}>↗</button>
          {#if canEdit}<RemoveButton label={`Remove pin ${pinName(pin, i)}`} onclick={() => remove(pin)} />{/if}
        </div>
      </div>
    {:else}
      <p class="text-xs muted">None. {input ? "Things come into this sheet" : "Things leave this sheet"} through pins on its {input ? "left" : "right"} edge.</p>
    {/each}
  </section>
{/each}

