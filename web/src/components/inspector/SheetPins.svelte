<script lang="ts">
  import Help from "./Help.svelte";
  import ChevronRight from "@lucide/svelte/icons/chevron-right";
  import { addSheetPin, app, edit, showNode } from "../../lib/state.svelte";
  import { RESOURCE_VAR } from "../../lib/kinds";
  import { pinName, sheetPins, type Edit, type GraphNode, type Sheet } from "../../lib/types";
  import AddButton from "../controls/AddButton.svelte";
  import PinMark from "../controls/PinMark.svelte";
  import RemoveButton from "../controls/RemoveButton.svelte";
  import PinFields from "./PinFields.svelte";

  /**
   * A sheet's pins, inputs and outputs, in the order its node shows them, each a group like a device's inputs and
   * outputs: its name, what it carries and (signal outputs) what it gives while the sheet is off. Removing one takes
   * its wires inside along (undo brings both back); wires on the sheet's node stay, and come back to the pin with it.
   * Adding one here keeps you here: its group opens with its Name box focused (picked on the canvas, a pin has a panel
   * of its own, with the same fields and its activity).
   */
  let { sheet }: { sheet: Sheet } = $props();

  const canEdit = $derived(app.snapshot?.canEdit ?? false);

  /** Which pins' groups are open, by pin: a lone one starts open. Kept here, not worked out (see DeviceNodeEditor). */
  let openPins = $state<Record<string, boolean>>({});

  async function add(input: boolean) {
    const id = await addSheetPin(sheet, input, false);
    if (id) openPins[id] = true;
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
      <h3 class="panel-title flex items-center gap-1.5"><PinMark solid={input} /><span class="trim-text">{input ? "Input pins (left)" : "Output pins (right)"}</span></h3>
      {#if canEdit}
        <AddButton inset label={input ? "Add an input pin" : "Add an output pin"} onclick={() => add(input)} />
      {/if}
    </div>
    {#each pins as pin, i (pin.id)}
      {@const s = pin.settings}
      {@const resource = s.kind === "sheet_input" || s.kind === "sheet_output" ? s.resource : undefined}
      <details class="box" bind:open={() => openPins[pin.id] ?? pins.length === 1, (v) => (openPins[pin.id] = v)}>
        <!-- The ✕ sits in the header, so it shows open or closed; clicking it doesn't open or close the group. -->
        <summary class="flex cursor-pointer items-center gap-2 text-sm">
          <ChevronRight size={14} class="disclosure shrink-0 muted" />
          <PinMark solid={input} color={resource ? RESOURCE_VAR[resource] : "var(--muted)"} />
          <span class="trim-text min-w-0 flex-1 truncate">{pinName(pin, i)}</span>
          {#if canEdit}
            <RemoveButton label={`Remove pin ${pinName(pin, i)}`} onclick={(e) => (e.preventDefault(), remove(pin))} />
          {/if}
        </summary>
        <div class="mt-2 flex flex-col gap-2">
          <PinFields {sheet} {pin} />
          <!-- Picks it on the canvas: its own panel, with its activity. -->
          <button class="btn self-start text-sm" onclick={() => showNode(sheet.id, pin.id)}>Show on the canvas</button>
        </div>
      </details>
    {:else}
      <p class="text-xs muted">None.</p>
      <Help>{input ? "Things come into this sheet" : "Things leave this sheet"} through pins on its {input ? "left" : "right"} edge.</Help>
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
