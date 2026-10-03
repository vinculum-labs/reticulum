<script lang="ts">
  import Scissors from "@lucide/svelte/icons/scissors";
  import { app } from "../../lib/state.svelte";
  import RemoveButton from "../controls/RemoveButton.svelte";
  import { RESOURCE_VAR, defaultSettings, formatRate, offeredResources, sideLabel } from "../../lib/kinds";
  import { sideId } from "../../lib/nameUuid";
  import PinMark from "../controls/PinMark.svelte";
  import AddButton from "../controls/AddButton.svelte";
  import { posKey, type Device, type DeviceInput, type DeviceNodeSettings, type DeviceOutput, type Resource, type Sheet } from "../../lib/types";
  import Field from "./Field.svelte";
  import SideEditor from "./SideEditor.svelte";
  import LimitsEditor from "./LimitsEditor.svelte";
  import AmountEditor from "./AmountEditor.svelte";
  import ChevronRight from "@lucide/svelte/icons/chevron-right";
  import DevicePicker from "./DevicePicker.svelte";
  import BlockPicker from "./BlockPicker.svelte";
  import { nowhere, placeless } from "../../lib/blueprints";
  import { unwiredSides, withoutSides } from "../../lib/prune";
  import WhileOffField from "./WhileOffField.svelte";

  let {
    settings,
    sheet,
    nodeId,
    disabled,
    onchange,
    slot = false,
    onshowside,
  }: {
    settings: DeviceNodeSettings;
    /** A slot of a blueprint: the device picked only gives the kind of block (and where layouts measure from). */
    slot?: boolean;
    /** Shows one input's or output's graph (a click on the rate in its header). */
    onshowside?: (direction: "in" | "out", index: number) => void;
    sheet: Sheet;
    nodeId: string;
    disabled: boolean;
    onchange: (s: DeviceNodeSettings, removed?: { in?: number[]; out?: number[] }, label?: string) => void;
  } = $props();

  const devices = $derived(app.snapshot?.devices ?? []);
  const key = $derived(posKey(settings.device));
  const device = $derived(devices.find((d) => posKey(d.pos) === key));
  const inputs = $derived(settings.inputs ?? []);
  const outputs = $derived(settings.outputs ?? []);
  const primary = $derived<Resource>(
    offeredResources(device?.resources)[0] ?? "item",
  );

  function setInput(i: number, next: DeviceInput) {
    onchange({ ...settings, inputs: inputs.map((x, j) => (j === i ? next : x)) });
  }

  function setOutput(i: number, next: DeviceOutput) {
    onchange({ ...settings, outputs: outputs.map((x, j) => (j === i ? next : x)) });
  }

  /**
   * Which groups are open, by number: a lone one starts open, one just added opens. Kept here, since an `open` worked
   * out from the list would be put back (closing what was opened) on every change to the node.
   */
  let openIn = $state<Record<number, boolean>>({});
  let openOut = $state<Record<number, boolean>>({});

  /** The open states with group i gone: those after it move up one. */
  function without(open: Record<number, boolean>, i: number): Record<number, boolean> {
    const next: Record<number, boolean> = {};
    for (const [k, v] of Object.entries(open)) {
      const j = Number(k);
      if (j !== i) next[j > i ? j - 1 : j] = v;
    }
    return next;
  }

  /** Removes input or output i; the caller drops its wires and renumbers the wires on later ones. */
  function removeSide(direction: "in" | "out", i: number) {
    const next =
      direction === "in" ? { ...settings, inputs: inputs.filter((_, j) => j !== i) } : { ...settings, outputs: outputs.filter((_, j) => j !== i) };
    change(next, direction === "in" ? { in: [i] } : { out: [i] }, `remove ${direction === "in" ? "input" : "output"}`);
  }

  /** A change that removes inputs or outputs: the groups' open states move up with the ones left. */
  function change(next: DeviceNodeSettings, removed: { in?: number[]; out?: number[] }, label: string) {
    for (const i of [...(removed.in ?? [])].sort((a, b) => b - a)) openIn = without(openIn, i);
    for (const i of [...(removed.out ?? [])].sort((a, b) => b - a)) openOut = without(openOut, i);
    onchange(next, removed, label);
  }

  /** Inputs and outputs with no wire on them. */
  const unwired = $derived(unwiredSides(sheet, { id: nodeId, settings }));
  const unwiredCount = $derived(unwired.in.length + unwired.out.length);

  /**
   * Points the node at another device (a slot: another kind of block, from a device or the catalog) and re-pins it:
   * inputs and outputs with wires stay (a mismatch shows as their warning), the rest go, and the new block's usual ones
   * are added where no kept one already covers them (from the catalog, nothing is known of it: items in and out). One
   * undoable change.
   */
  function repick(picked: Device | { block: string }) {
    const device = "pos" in picked ? picked : null;
    const fresh = (device
      ? defaultSettings("device", device, offeredResources(device.resources)[0] ?? "item")
      : { kind: "device", device: nowhere(), inputs: [{ resource: "item" }], outputs: [{ resource: "item", per_operation: 0 }] }) as DeviceNodeSettings;
    const kept = withoutSides(settings, unwired);
    const faces = (f: string[] | undefined) => (f ? [...f].sort().join() : "all");
    const covers = (a: DeviceInput | DeviceOutput, b: DeviceInput | DeviceOutput) => a.resource === b.resource && faces(a.faces) === faces(b.faces);
    const keptIn = kept.inputs ?? [];
    const keptOut = kept.outputs ?? [];
    change(
      {
        ...settings,
        device: fresh.device,
        ...(slot ? { block: picked.block } : {}),
        inputs: [...keptIn, ...(fresh.inputs ?? []).filter((f) => !keptIn.some((k) => covers(k, f)))],
        outputs: [...keptOut, ...(fresh.outputs ?? []).filter((f) => !keptOut.some((k) => covers(k, f)))],
      },
      unwired,
      slot ? "slot block" : "device",
    );
  }

  /** Removes every input and output nothing is wired to, as one undoable change. */
  function prune() {
    change(withoutSides(settings, unwired), unwired, "prune");
  }
</script>

{#if slot}
  {@const from = placeless(settings.device) ? undefined : devices.find((d) => posKey(d.pos) === key)}
  <Field
    label="Kind of block"
    hint={from || !placeless(settings.device)
      ? "Copies fill this slot with this kind of block. Fill the rest by layout measures from where the device it came from sits."
      : "Copies fill this slot with this kind of block. Picked from the catalog, it has no place in a layout: fill it on each copy."}
  >
    <BlockPicker {devices} block={settings.block} {from} {disabled} onchange={(pick) => repick(pick.device ?? { block: pick.block })} />
  </Field>
{:else}
  <Field label="Device">
    <DevicePicker {devices} value={settings.device} {disabled} onchange={repick} />
  </Field>
{/if}

<button
  class="btn flex items-center gap-1 self-start text-sm"
  disabled={disabled || unwiredCount === 0}
  title={unwiredCount
    ? `Remove the ${unwiredCount} input${unwiredCount === 1 ? "" : "s"} and output${unwiredCount === 1 ? "" : "s"} nothing is wired to`
    : "Every input and output has a wire"}
  onclick={prune}><Scissors size={14} />Prune pins{unwiredCount ? ` (${unwiredCount})` : ""}</button
>

<!-- What a side is moving now, in its header (open or closed); a click shows its graph. Nothing while it is idle. -->
{#snippet rate(direction: "in" | "out", index: number, resource: Resource)}
  {@const value = slot ? 0 : (app.rates[sideId(nodeId, direction, index)] ?? 0)}
  {#if value > 0}
    <button
      type="button"
      class="side-rate trim-text shrink-0 rounded px-1 text-xs tabular-nums muted"
      title={`Show its graph below`}
      onclick={(e) => (e.preventDefault(), onshowside?.(direction, index))}>{formatRate(value, resource, app.rateUnit)}</button
    >
  {/if}
{/snippet}

<section class="flex flex-col gap-2">
  <div class="flex items-center justify-between">
    <h3 class="panel-title flex items-center gap-1.5"><PinMark /><span class="trim-text">Inputs (left)</span></h3>
    <AddButton inset label="Add an input" {disabled} onclick={() => ((openIn[inputs.length] = true), onchange({ ...settings, inputs: [...inputs, { resource: primary }] }))} />
  </div>
  {#each inputs as input, i}
    <details class="box" bind:open={() => openIn[i] ?? inputs.length === 1, (v) => (openIn[i] = v)}>
      <!-- The ✕ sits in the header, so it shows open or closed; clicking it doesn't open or close the group. -->
      <summary class="flex cursor-pointer items-center gap-2 text-sm">
        <ChevronRight size={14} class="disclosure shrink-0 muted" />
        <PinMark color={RESOURCE_VAR[input.resource]} />
        <span class="trim-text min-w-0 flex-1 truncate">Input {i + 1}: {sideLabel(input)}</span>
        {@render rate("in", i, input.resource)}
        <RemoveButton label="Remove input {i + 1}" {disabled} onclick={(e) => (e.preventDefault(), removeSide("in", i))} />
      </summary>
      <div class="mt-2 flex flex-col gap-3">
        <SideEditor side={input} {device} {disabled} onchange={(side) => setInput(i, { ...input, ...side })} />
        {#if input.resource === "redstone"}
          <p class="text-xs muted">The connectors touching the ticked faces (all of them when none are ticked) power the device with what is wired in, 0 to 15.</p>
          <WhileOffField value={input.while_off} {disabled} onchange={(while_off) => setInput(i, { ...input, while_off })} />
        {:else if input.resource === "me"}
          <p class="text-xs muted">Wire an ME output of another device here to join the two AE2 networks through the connectors touching these faces.</p>
        {:else}
          <LimitsEditor limits={input.caps ?? []} keep={false} resource={input.resource} device={settings.device} {disabled} onchange={(caps) => setInput(i, { ...input, caps })} />
        {/if}
      </div>
    </details>
  {:else}
    <p class="text-xs muted">No inputs: nothing is put into this device.</p>
  {/each}
</section>

<section class="flex flex-col gap-2">
  <div class="flex items-center justify-between">
    <h3 class="panel-title flex items-center gap-1.5"><PinMark solid /><span class="trim-text">Outputs (right)</span></h3>
    <AddButton inset label="Add an output" {disabled} onclick={() => ((openOut[outputs.length] = true), onchange({ ...settings, outputs: [...outputs, { resource: primary, per_operation: 0 }] }))} />
  </div>
  {#each outputs as output, i}
    <details class="box" bind:open={() => openOut[i] ?? outputs.length === 1, (v) => (openOut[i] = v)}>
      <!-- The ✕ sits in the header, so it shows open or closed; clicking it doesn't open or close the group. -->
      <summary class="flex cursor-pointer items-center gap-2 text-sm">
        <ChevronRight size={14} class="disclosure shrink-0 muted" />
        <PinMark solid color={RESOURCE_VAR[output.resource]} />
        <span class="trim-text min-w-0 flex-1 truncate">Output {i + 1}: {sideLabel(output)}</span>
        {@render rate("out", i, output.resource)}
        <RemoveButton label="Remove output {i + 1}" {disabled} onclick={(e) => (e.preventDefault(), removeSide("out", i))} />
      </summary>
      <div class="mt-2 flex flex-col gap-3">
        <SideEditor side={output} {device} {disabled} onchange={(side) => setOutput(i, { ...output, ...side })} />
        {#if output.resource === "redstone"}
          <label class="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={output.comparator === true} {disabled} onchange={(e) => setOutput(i, { ...output, comparator: e.currentTarget.checked })} />
            Comparator value instead (how full it is, and the like)
          </label>
          <p class="text-xs muted">The redstone the device gives out of the ticked faces (the strongest face when none are ticked), 0 to 15.</p>
        {:else if output.resource === "me"}
          <p class="text-xs muted">
            Wire it to an ME input of another device to join the two AE2 networks: 32 channels at most, 8 through a normal cable. The pin shows the
            channels through it and the fullest cable on the way to the ME controller.
          </p>
        {:else}
          <AmountEditor settings={output} sheetInterval={sheet.interval ?? 10} {disabled} onchange={(patch) => setOutput(i, { ...output, ...patch })} />
          <LimitsEditor limits={output.keep ?? []} keep={true} resource={output.resource} device={settings.device} {disabled} onchange={(keep) => setOutput(i, { ...output, keep })} />
        {/if}
      </div>
    </details>
  {:else}
    <p class="text-xs muted">No outputs: nothing is taken out of this device.</p>
  {/each}
</section>

<style>
  .side-rate:hover {
    background: var(--panel-2);
    color: var(--ink);
  }
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
