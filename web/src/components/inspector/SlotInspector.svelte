<script lang="ts">
  import { app, instanceRun, deviceAt, deviceName, fillSlot, homeCheck, setBindings, toast } from "../../lib/state.svelte";
  import { blockName, fillByLayout, inInstance, slotName, slotsOf } from "../../lib/blueprints";
  import { RESOURCE_UNIT } from "../../lib/kinds";
  import { posKey, type InstanceSettings, type Device, type GraphNode, type Sheet } from "../../lib/types";
  import DeviceIcon from "../DeviceIcon.svelte";
  import Segmented from "../controls/Segmented.svelte";
  import DevicePicker from "./DevicePicker.svelte";
  import Field from "./Field.svelte";
  import InspectorPanel from "./InspectorPanel.svelte";
  import ThroughputSummary from "./ThroughputSummary.svelte";
  import Chart from "./Chart.svelte";

  /** A slot of an open instance: which block fills it, how far it is turned, and filling the rest by layout. */
  let { sheet, instance, blueprint, slot }: { sheet: Sheet; instance: GraphNode & { settings: InstanceSettings }; blueprint: Sheet; slot: GraphNode } = $props();

  const canEdit = $derived(app.snapshot?.canEdit ?? false);
  const bindings = $derived(instance.settings.bindings ?? {});
  const binding = $derived(bindings[slot.id]);
  const filler = $derived(binding ? deviceAt(posKey(binding.device)) : undefined);
  const block = $derived(slot.settings.kind === "device" ? slot.settings.block : undefined);
  const active = $derived((app.snapshot?.devices ?? []).filter((d) => d.state === "active"));
  const unit = $derived(slot.settings.kind === "device" && slot.settings.outputs?.[0] ? RESOURCE_UNIT[slot.settings.outputs[0].resource] : "units");
  const empty = $derived(slotsOf(blueprint).filter((s) => !bindings[s.id]).length);

  function fill(device: Device) {
    fillSlot(sheet, instance, blueprint, slot, device);
  }

  function turn(turns: number) {
    if (binding) setBindings(sheet, instance, (b) => (b[slot.id] ? { ...b, [slot.id]: { device: b[slot.id].device, ...(turns ? { turns } : {}) } } : b), "turn slot");
  }

  function clear() {
    setBindings(
      sheet,
      instance,
      (b) => {
        const { [slot.id]: _gone, ...rest } = b;
        return rest;
      },
      `empty ${slotName(slot)}`,
    );
  }

  /** Fills the other empty slots from this one: same layout, turned the same, exact kinds of blocks, unused ones only. */
  async function fillRest() {
    const result = fillByLayout(blueprint, bindings, slot.id, app.snapshot!.devices, homeCheck());
    if (!result.filled) {
      toast("No other slot's block is where the layout says (the same kind of block, unused, at the same place from this one).", "info");
      return;
    }
    const ok = await setBindings(sheet, instance, () => result.bindings, "fill by layout");
    if (ok) toast(`Filled ${result.filled} of ${empty} empty slot${empty === 1 ? "" : "s"}.`, "info");
  }
</script>

<InspectorPanel title={slotName(slot)} subtitle={`A slot for: ${blockName(block)}`}>
  {#snippet icon()}<DeviceIcon item={filler?.icon ?? block} size={32} />{/snippet}

  <Field label="Filled by" hint="Or drop a device from the list on the slot. Any block can fill it — one that doesn't fit shows as a problem.">
    <DevicePicker devices={active} value={binding?.device} placeholder="Empty: choose a block…" disabled={!canEdit} onchange={fill} />
  </Field>
  {#if binding && !filler}
    <p class="box box-warn text-sm" role="status">The block filling it is gone from the network.</p>
  {:else if filler && block && filler.block !== block}
    <p class="box box-warn text-sm" role="status">It takes {blockName(block)}; {deviceName(filler)} is a {blockName(filler.block)}. Its flows may not fit it.</p>
  {/if}
  {#if binding}
    <Field label="Turned" hint="For a set built facing another way: the slot's faces turn with it (seen from above, clockwise).">
      <Segmented
        label="Turned"
        size="xs"
        value={String(((binding.turns ?? 0) % 4 + 4) % 4)}
        disabled={!canEdit}
        options={[
          { value: "0", label: "0°" },
          { value: "1", label: "90°" },
          { value: "2", label: "180°" },
          { value: "3", label: "270°" },
        ]}
        onchange={(v) => turn(Number(v))}
      />
    </Field>
    {#if canEdit}
      <div class="flex flex-wrap gap-2">
        <button class="btn text-sm" disabled={empty === 0} onclick={fillRest}
          title="Finds each empty slot's block at the same place from this one as in the blueprint: the same kind of block, not used elsewhere">
          Fill the rest by layout
        </button>
        <button class="btn text-sm" onclick={clear}>Empty this slot</button>
      </div>
    {/if}
  {/if}
  <!-- What the block filling it is moving (kept under the instance's name for this slot), and its graph. -->
  {#if binding}
    {@const run = instanceRun()}
    <ThroughputSummary id={run ? inInstance(run, slot.id) : slot.id} />
    <Chart item={run ? inInstance(run, slot.id) : slot.id} unit={unit} />
  {/if}
</InspectorPanel>
