<script lang="ts">
  import { RESOURCE_LABEL, offeredResources } from "../../lib/kinds";
  import { SIDES, type Device, type Face, type Resource, type Side } from "../../lib/types";
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

  function setSlots(first: string, last: string) {
    const next = { ...side };
    if (first === "" && last === "") delete next.slots;
    else next.slots = { first: Math.max(0, Number(first) || 0), last: Math.max(0, last === "" ? 9999 : Number(last)) };
    onchange(next);
  }
</script>

<Field label="Resource">
  <select value={side.resource} {disabled} onchange={(e) => onchange({ ...side, resource: e.currentTarget.value as Resource, faces: [] })}>
    {#each choices as r}<option value={r}>{RESOURCE_LABEL[r]}</option>{/each}
  </select>
</Field>

<div class="flex flex-col gap-1">
  <span class="text-xs font-medium muted">Faces</span>
  {#if offered.length === 0}
    <span class="text-xs" style="color: var(--warn)">This device offers no {side.resource} on any face{device?.loaded === false ? " (not loaded)" : ""}.</span>
  {:else}
    <div class="grid grid-cols-2 gap-x-2 gap-y-0.5">
      {#each offered as face}
        <label class="flex items-center gap-1.5 text-sm">
          <input type="checkbox" checked={chosen.has(face)} {disabled} onchange={(e) => toggleFace(face, e.currentTarget.checked)} />
          {face}{#if device?.faceNames?.[face]}<span class="truncate muted">· {device.faceNames[face]}</span>{/if}
        </label>
      {/each}
    </div>
    <span class="text-xs muted">{chosen.size === 0 ? "None ticked: every face the device offers (its own side settings decide)." : "Only the ticked faces."}</span>
  {/if}
</div>

{#if side.resource === "item"}
  <div class="flex flex-col gap-1">
    <span class="text-xs font-medium muted">Slots</span>
    <div class="flex items-center gap-2 text-sm">
      <input type="number" min="0" class="w-20" placeholder="first" value={side.slots?.first ?? ""} {disabled}
        onchange={(e) => setSlots(e.currentTarget.value, String(side.slots?.last ?? ""))} />
      to
      <input type="number" min="0" class="w-20" placeholder="last" value={side.slots?.last ?? ""} {disabled}
        onchange={(e) => setSlots(String(side.slots?.first ?? ""), e.currentTarget.value)} />
    </div>
    <span class="text-xs muted">Leave empty for all slots.</span>
  </div>
{/if}
