<script lang="ts">
  import { app } from "../../lib/state.svelte";
  import { offeredResources, sideLabel } from "../../lib/kinds";
  import { posKey, type DeviceInput, type DeviceNodeSettings, type DeviceOutput, type Resource, type Sheet } from "../../lib/types";
  import Field from "./Field.svelte";
  import SideEditor from "./SideEditor.svelte";
  import LimitsEditor from "./LimitsEditor.svelte";
  import AmountEditor from "./AmountEditor.svelte";
  import DevicePicker from "./DevicePicker.svelte";

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
    onchange: (s: DeviceNodeSettings, removed?: { direction: "in" | "out"; index: number }) => void;
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
    const wires = (sheet.wires ?? []).filter((w) => (direction === "in" ? w.to === nodeId && (w.input ?? 0) === i : w.from === nodeId && (w.output ?? 0) === i));
    if (wires.length && !confirm(`Remove this ${direction === "in" ? "input" : "output"} and its ${wires.length} wire(s)?`)) return;
    const next =
      direction === "in" ? { ...settings, inputs: inputs.filter((_, j) => j !== i) } : { ...settings, outputs: outputs.filter((_, j) => j !== i) };
    onchange(next, { direction, index: i });
  }
</script>

<Field label="Device">
  <DevicePicker {devices} value={settings.device} {disabled} onchange={(d) => onchange({ ...settings, device: d.pos })} />
</Field>

<section class="flex flex-col gap-2">
  <div class="flex items-center justify-between">
    <h3 class="panel-title">Inputs (left)</h3>
    <button class="btn text-sm" {disabled} onclick={() => onchange({ ...settings, inputs: [...inputs, { resource: primary }] })}>+ Input</button>
  </div>
  {#each inputs as input, i}
    <details class="rounded-md border p-2" style="border-color: var(--line)" open={inputs.length === 1}>
      <summary class="flex cursor-pointer items-center justify-between text-sm">
        <span>Input {i + 1}: {sideLabel(input)}</span>
        <button class="text-sm muted" {disabled} onclick={(e) => (e.preventDefault(), removeSide("in", i))} title="Remove input">✕</button>
      </summary>
      <div class="mt-2 flex flex-col gap-3">
        <SideEditor side={input} {device} {disabled} onchange={(side) => setInput(i, { ...input, ...side })} />
        <LimitsEditor limits={input.caps ?? []} keep={false} resource={input.resource} {disabled} onchange={(caps) => setInput(i, { ...input, caps })} />
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
    <details class="rounded-md border p-2" style="border-color: var(--line)" open={outputs.length === 1}>
      <summary class="flex cursor-pointer items-center justify-between text-sm">
        <span>Output {i + 1}: {sideLabel(output)}</span>
        <button class="text-sm muted" {disabled} onclick={(e) => (e.preventDefault(), removeSide("out", i))} title="Remove output">✕</button>
      </summary>
      <div class="mt-2 flex flex-col gap-3">
        <SideEditor side={output} {device} {disabled} onchange={(side) => setOutput(i, { ...output, ...side })} />
        <AmountEditor settings={output} sheetInterval={sheet.interval ?? 10} {disabled} onchange={(patch) => setOutput(i, { ...output, ...patch })} />
        <LimitsEditor limits={output.keep ?? []} keep={true} resource={output.resource} {disabled} onchange={(keep) => setOutput(i, { ...output, keep })} />
      </div>
    </details>
  {:else}
    <p class="text-xs muted">No outputs: nothing is taken out of this device.</p>
  {/each}
</section>
