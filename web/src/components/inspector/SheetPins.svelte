<script lang="ts">
  import ChevronRight from "@lucide/svelte/icons/chevron-right";
  import { addSheetPin, app, edit, rebase, showNode } from "../../lib/state.svelte";
  import { PIN_CARRIES, RESOURCE_VAR } from "../../lib/kinds";
  import { pinName, sheetPins, type Edit, type GraphNode, type Resource, type Sheet } from "../../lib/types";
  import AddButton from "../controls/AddButton.svelte";
  import PinMark from "../controls/PinMark.svelte";
  import RemoveButton from "../controls/RemoveButton.svelte";
  import Select from "../controls/Select.svelte";
  import TextField from "../controls/TextField.svelte";
  import Field from "./Field.svelte";
  import WhileOffField from "./WhileOffField.svelte";

  /**
   * A sheet's pins, inputs and outputs, in the order its node shows them, each a group like a device's inputs and
   * outputs: its name, what it carries and (signal outputs) what it gives while the sheet is off. Removing one takes
   * its wires inside along (undo brings both back); wires on the sheet's node stay, and come back to the pin with it.
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
  <section class="flex flex-col gap-2">
    <div class="flex items-center justify-between">
      <h3 class="panel-title flex items-center gap-1.5"><PinMark solid={input} />{input ? "Input pins (left)" : "Output pins (right)"}</h3>
      {#if canEdit}
        <AddButton label={input ? "Add an input pin" : "Add an output pin"} onclick={() => addSheetPin(sheet, input)} />
      {/if}
    </div>
    {#each pins as pin, i (pin.id)}
      {@const s = pin.settings}
      {@const resource = s.kind === "sheet_input" || s.kind === "sheet_output" ? s.resource : undefined}
      <details class="box" open={pins.length === 1}>
        <!-- The ✕ sits in the header, so it shows open or closed; clicking it doesn't open or close the group. -->
        <summary class="flex cursor-pointer items-center gap-2 text-sm">
          <ChevronRight size={14} class="disclosure shrink-0 muted" />
          <PinMark solid={input} color={resource ? RESOURCE_VAR[resource] : "var(--muted)"} />
          <span class="min-w-0 flex-1 truncate">{pinName(pin, i)}</span>
          {#if canEdit}
            <RemoveButton label={`Remove pin ${pinName(pin, i)}`} onclick={(e) => (e.preventDefault(), remove(pin))} />
          {/if}
        </summary>
        <div class="mt-2 flex flex-col gap-2">
          <Field label="Name">
            <TextField value={pinName(pin, i)} disabled={!canEdit} oncommit={(name) => save(pin, { name }, "rename pin")} />
          </Field>
          <Field label="Carries" hint="Wires of other kinds won't connect to it. Anything: whatever is wired to it.">
            <Select
              value={resource ?? ""}
              disabled={!canEdit}
              options={PIN_CARRIES}
              onchange={(v) => save(pin, { resource: (v || undefined) as Resource | undefined }, "pin type")}
            />
          </Field>
          {#if s.kind === "sheet_output" && (!resource || resource === "redstone")}
            <WhileOffField value={s.while_off} disabled={!canEdit} onchange={(while_off) => save(pin, { while_off }, "pin while off")} />
          {/if}
          <button class="btn self-start text-sm" onclick={() => showNode(sheet.id, pin.id)}>Show on the canvas</button>
        </div>
      </details>
    {:else}
      <p class="text-xs muted">None. {input ? "Things come into this sheet" : "Things leave this sheet"} through pins on its {input ? "left" : "right"} edge.</p>
    {/each}
  </section>
{/each}

<style>
  /* A header laid out as a row loses the browser's ▸: this one turns when the group is open. */
  summary {
    list-style: none;
  }
  summary::-webkit-details-marker {
    display: none;
  }
  details[open] > summary :global(.disclosure) {
    transform: rotate(90deg);
  }
  summary :global(.disclosure) {
    transition: transform 0.15s;
  }
</style>
