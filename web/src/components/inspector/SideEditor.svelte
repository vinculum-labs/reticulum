<script lang="ts">
  import Help from "./Help.svelte";
  import { RESOURCE_LABEL, RESOURCE_VAR, offeredResources } from "../../lib/kinds";
  import { SIDES, type Device, type Face, type Resource, type Side } from "../../lib/types";
  import NumberField from "../controls/NumberField.svelte";
  import Select from "../controls/Select.svelte";
  import Field from "./Field.svelte";

  let { side, device, disabled, onchange }: { side: Side; device: Device | undefined; disabled: boolean; onchange: (side: Side) => void } =
    $props();

  // Faces that offer this resource; unsided only when no face does (as the engine resolves "auto").
  const offered = $derived.by(() => {
    const faces = device?.resources?.[side.resource] ?? [];
    const sides = faces.filter((f) => f !== "unsided");
    return sides.length ? sides : faces;
  });
  const chosen = $derived(new Set(side.faces ?? []));
  // Items, fluids, energy and redstone are always offered; chemicals (Mekanism) and ME (AE2) only where the device has them.
  const choices = $derived.by(() => {
    const offered = offeredResources(device?.resources);
    const list: Resource[] = ["item", "fluid", "energy"];
    if (side.resource === "chemical" || offered.includes("chemical")) list.push("chemical");
    list.push("redstone");
    if (side.resource === "me" || offered.includes("me")) list.push("me");
    return list;
  });

  function toggleFace(face: Face, on: boolean) {
    const faces = new Set(side.faces ?? []);
    on ? faces.add(face) : faces.delete(face);
    onchange({ ...side, faces: [...SIDES, "unsided" as Face].filter((f) => faces.has(f)) });
  }

  /** Either end left empty: from the first slot, or to the last. Both empty: all slots. */
  function setSlots(first: number | undefined, last: number | undefined) {
    const next = { ...side };
    if (first === undefined && last === undefined) delete next.slots;
    else next.slots = { first: first ?? 0, last: last ?? 9999 };
    onchange(next);
  }
</script>

<Field label="Resource">
  <Select
    value={side.resource}
    {disabled}
    options={choices.map((r) => ({ value: r, label: RESOURCE_LABEL[r], swatch: RESOURCE_VAR[r] }))}
    onchange={(resource) => onchange({ ...side, resource, faces: [] })}
  />
</Field>

<fieldset class="flex flex-col gap-1">
  <legend class="field-label mb-1">Faces</legend>
  {#if offered.length === 0}
    <span class="text-xs text-warn">This device offers no {side.resource} on any face{device?.loaded === false ? " (not loaded)" : ""}.</span>
  {:else}
    <div class="grid grid-cols-2 gap-x-2 gap-y-0.5">
      {#each offered as face}
        <label class="flex items-center gap-1.5 text-sm">
          <input type="checkbox" checked={chosen.has(face)} {disabled} onchange={(e) => toggleFace(face, e.currentTarget.checked)} />
          <span class="trim-text min-w-0 truncate">{face}{#if device?.faceNames?.[face]}<span class="muted"> · {device.faceNames[face]}</span>{/if}</span>
        </label>
      {/each}
    </div>
    {#if chosen.size === 0}<Help>None ticked: every face the device offers (its own side settings decide).</Help>{/if}
  {/if}
</fieldset>

{#if side.resource === "item"}
  <Field label="Slots" hint="Leave empty for all slots." group>
    <div class="flex items-center gap-2 text-sm">
      <NumberField value={side.slots?.first} min={0} max={9999} optional placeholder="first" {disabled} title="First slot"
        oncommit={(v) => setSlots(v, side.slots?.last)} />
      to
      <NumberField value={side.slots?.last} min={0} max={9999} optional placeholder="last" {disabled} title="Last slot"
        oncommit={(v) => setSlots(side.slots?.first, v)} />
    </div>
  </Field>
{/if}
