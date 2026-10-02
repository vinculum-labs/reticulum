<script lang="ts">
  import { app, deviceAt, deviceName, deviceUses, setBindings, toast } from "../../lib/state.svelte";
  import { blockName, fillByLayout, slotName, slotsOf } from "../../lib/blueprints";
  import { posKey, type CopySettings, type Device, type GraphNode, type Sheet } from "../../lib/types";
  import DeviceIcon from "../DeviceIcon.svelte";
  import Segmented from "../controls/Segmented.svelte";
  import DevicePicker from "./DevicePicker.svelte";
  import Field from "./Field.svelte";
  import InspectorPanel from "./InspectorPanel.svelte";

  /** A slot of an open copy: which block fills it, how far it is turned, and filling the rest by layout. */
  let { sheet, copy, blueprint, slot }: { sheet: Sheet; copy: GraphNode & { settings: CopySettings }; blueprint: Sheet; slot: GraphNode } = $props();

  const canEdit = $derived(app.snapshot?.canEdit ?? false);
  const bindings = $derived(copy.settings.bindings ?? {});
  const binding = $derived(bindings[slot.id]);
  const filler = $derived(binding ? deviceAt(posKey(binding.device)) : undefined);
  const block = $derived(slot.settings.kind === "device" ? slot.settings.block : undefined);
  const active = $derived((app.snapshot?.devices ?? []).filter((d) => d.state === "active"));
  const empty = $derived(slotsOf(blueprint).filter((s) => !bindings[s.id]).length);

  function fill(device: Device) {
    setBindings(sheet, copy, { ...bindings, [slot.id]: { device: device.pos, ...(binding?.turns ? { turns: binding.turns } : {}) } }, `fill ${slotName(slot)}`);
  }

  function turn(turns: number) {
    if (binding) setBindings(sheet, copy, { ...bindings, [slot.id]: { device: binding.device, ...(turns ? { turns } : {}) } }, "turn slot");
  }

  function clear() {
    const { [slot.id]: _gone, ...rest } = bindings;
    setBindings(sheet, copy, rest, `empty ${slotName(slot)}`);
  }

  /** Fills the other empty slots from this one: same layout, turned the same, exact kinds of block, unused ones only. */
  async function fillRest() {
    const uses = deviceUses(app.snapshot!);
    const placed = (d: Device) => (uses.get(posKey(d.pos)) ?? []).some((u) => u.home);
    const result = fillByLayout(blueprint, bindings, slot.id, app.snapshot!.devices, placed);
    if (!result.filled) {
      toast("No other slot's block is where the layout says (the same kind of block, unused, at the same place from this one).", "info");
      return;
    }
    const ok = await setBindings(sheet, copy, result.bindings, "fill by layout");
    if (ok) toast(`Filled ${result.filled} of ${empty - 1} empty slot${empty - 1 === 1 ? "" : "s"}.`, "info");
  }
</script>

<InspectorPanel title={slotName(slot)} subtitle={`A slot for: ${blockName(block)}`}>
  {#snippet icon()}<DeviceIcon item={filler?.icon ?? block} size={32} />{/snippet}

  <Field label="Filled by" hint="Or drop a device from the list on the slot. Any block can fill it; one that doesn't fit shows as a problem.">
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
</InspectorPanel>
