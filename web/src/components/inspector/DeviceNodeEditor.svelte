<script lang="ts">
  import Scissors from "@lucide/svelte/icons/scissors";
  import { app } from "../../lib/state.svelte";
  import RemoveButton from "../controls/RemoveButton.svelte";
  import { offeredResources, sideLabel } from "../../lib/kinds";
  import { posKey, type DeviceInput, type DeviceNodeSettings, type DeviceOutput, type Resource, type Sheet } from "../../lib/types";
  import Field from "./Field.svelte";
  import SideEditor from "./SideEditor.svelte";
  import LimitsEditor from "./LimitsEditor.svelte";
  import AmountEditor from "./AmountEditor.svelte";
  import DevicePicker from "./DevicePicker.svelte";
  import WhileOffField from "./WhileOffField.svelte";

  let {
    settings,
    sheet,
    nodeId,
    disabled,
    onchange,
  }: {
    settings: DeviceNodeSettings;
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

  /** Removes input or output i; the caller drops its wires and renumbers the wires on later ones. */
  function removeSide(direction: "in" | "out", i: number) {
    const next =
      direction === "in" ? { ...settings, inputs: inputs.filter((_, j) => j !== i) } : { ...settings, outputs: outputs.filter((_, j) => j !== i) };
    onchange(next, direction === "in" ? { in: [i] } : { out: [i] }, `remove ${direction === "in" ? "input" : "output"}`);
  }

  /** Inputs and outputs with no wire on them. */
  const unwired = $derived.by(() => {
    const wires = sheet.wires ?? [];
    return {
      in: inputs.map((_, i) => i).filter((i) => !wires.some((w) => w.to === nodeId && (w.input ?? 0) === i)),
      out: outputs.map((_, i) => i).filter((i) => !wires.some((w) => w.from === nodeId && (w.output ?? 0) === i)),
    };
  });
  const unwiredCount = $derived(unwired.in.length + unwired.out.length);

  /** Removes every input and output nothing is wired to, as one undoable change. */
  function prune() {
    const next = {
      ...settings,
      inputs: inputs.filter((_, i) => !unwired.in.includes(i)),
      outputs: outputs.filter((_, i) => !unwired.out.includes(i)),
    };
    onchange(next, unwired, "prune");
  }
</script>

<Field label="Device">
  <DevicePicker {devices} value={settings.device} {disabled} onchange={(d) => onchange({ ...settings, device: d.pos })} />
</Field>

<button
  class="btn flex items-center gap-1 self-start text-sm"
  disabled={disabled || unwiredCount === 0}
  title={unwiredCount
    ? `Remove the ${unwiredCount} input${unwiredCount === 1 ? "" : "s"} and output${unwiredCount === 1 ? "" : "s"} nothing is wired to`
    : "Every input and output has a wire"}
  onclick={prune}><Scissors size={14} />Prune unwired{unwiredCount ? ` (${unwiredCount})` : ""}</button
>

<section class="flex flex-col gap-2">
  <div class="flex items-center justify-between">
    <h3 class="panel-title">Inputs (left)</h3>
    <button class="btn text-sm" {disabled} onclick={() => onchange({ ...settings, inputs: [...inputs, { resource: primary }] })}>+ Input</button>
  </div>
  {#each inputs as input, i}
    <details class="box relative" open={inputs.length === 1}>
      <summary class="cursor-pointer pr-6 text-sm">Input {i + 1}: {sideLabel(input)}</summary>
      <RemoveButton class="absolute top-1.5 right-1.5" label="Remove input {i + 1}" {disabled} onclick={() => removeSide("in", i)} />
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
    <h3 class="panel-title">Outputs (right)</h3>
    <button class="btn text-sm" {disabled} onclick={() => onchange({ ...settings, outputs: [...outputs, { resource: primary, per_operation: 0 }] })}>+ Output</button>
  </div>
  {#each outputs as output, i}
    <details class="box relative" open={outputs.length === 1}>
      <summary class="cursor-pointer pr-6 text-sm">Output {i + 1}: {sideLabel(output)}</summary>
      <RemoveButton class="absolute top-1.5 right-1.5" label="Remove output {i + 1}" {disabled} onclick={() => removeSide("out", i)} />
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
