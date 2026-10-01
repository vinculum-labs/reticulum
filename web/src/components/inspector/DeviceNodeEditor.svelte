<script lang="ts">
  import { app } from "../../lib/state.svelte";
  import { confirmDialog } from "../../lib/dialog.svelte";
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
  async function removeSide(direction: "in" | "out", i: number) {
    const wires = (sheet.wires ?? []).filter((w) => (direction === "in" ? w.to === nodeId && (w.input ?? 0) === i : w.from === nodeId && (w.output ?? 0) === i));
    const side = direction === "in" ? "input" : "output";
    if (
      wires.length &&
      !(await confirmDialog({
        title: `Remove this ${side}?`,
        message: `Its ${wires.length} wire${wires.length === 1 ? "" : "s"} go${wires.length === 1 ? "es" : ""} with it.`,
        confirm: `Remove ${side}`,
        danger: true,
      }))
    )
      return;
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
        {#if input.resource === "redstone"}
          <p class="text-xs muted">The connectors touching the ticked faces (all of them when none are ticked) power the device with what is wired in, 0 to 15.</p>
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
    <details class="rounded-md border p-2" style="border-color: var(--line)" open={outputs.length === 1}>
      <summary class="flex cursor-pointer items-center justify-between text-sm">
        <span>Output {i + 1}: {sideLabel(output)}</span>
        <button class="text-sm muted" {disabled} onclick={(e) => (e.preventDefault(), removeSide("out", i))} title="Remove output">✕</button>
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
