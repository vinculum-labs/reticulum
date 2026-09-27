<script lang="ts">
  import { app, deviceName } from "../../lib/state.svelte";
  import { RESOURCE_LABEL } from "../../lib/kinds";
  import { posKey, SIDES, type Face, type PortFields, type Resource } from "../../lib/types";
  import Field from "./Field.svelte";

  let { port, disabled, onchange }: { port: PortFields; disabled: boolean; onchange: (port: PortFields) => void } = $props();

  const devices = $derived(app.snapshot?.devices ?? []);
  const deviceKey = $derived(posKey(port.device));
  const device = $derived(devices.find((d) => posKey(d.pos) === deviceKey));
  const candidates = $derived(devices.filter((d) => d.resources?.[port.resource]?.length || posKey(d.pos) === deviceKey));
  // Faces that offer this resource; unsided only when no face does (as the engine resolves "auto").
  const offered = $derived.by(() => {
    const faces = device?.resources?.[port.resource] ?? [];
    const sides = faces.filter((f) => f !== "unsided");
    return sides.length ? sides : faces;
  });
  const chosen = $derived(new Set(port.faces ?? []));

  function setResource(resource: Resource) {
    onchange({ ...port, resource, faces: [] });
  }

  function setDevice(key: string) {
    const d = devices.find((x) => posKey(x.pos) === key);
    if (d) onchange({ ...port, device: d.pos, faces: [] });
  }

  function toggleFace(face: Face, on: boolean) {
    const faces = new Set(port.faces ?? []);
    on ? faces.add(face) : faces.delete(face);
    onchange({ ...port, faces: SIDES.concat("unsided").filter((f) => faces.has(f)) });
  }

  function setSlots(first: string, last: string) {
    const next = { ...port };
    if (first === "" && last === "") delete next.slots;
    else next.slots = { first: Math.max(0, Number(first) || 0), last: Math.max(0, last === "" ? 9999 : Number(last)) };
    onchange(next);
  }
</script>

<Field label="Resource">
  <select value={port.resource} {disabled} onchange={(e) => setResource(e.currentTarget.value as Resource)}>
    {#each ["item", "fluid", "energy"] as r}<option value={r}>{RESOURCE_LABEL[r as Resource]}</option>{/each}
  </select>
</Field>

<Field label="Device">
  <select value={deviceKey} {disabled} onchange={(e) => setDevice(e.currentTarget.value)}>
    {#each candidates as d (posKey(d.pos))}
      <option value={posKey(d.pos)}>{deviceName(d)}{d.state !== "active" ? ` (${d.state})` : ""}</option>
    {/each}
  </select>
</Field>

<div class="flex flex-col gap-1">
  <span class="text-xs font-medium muted">Faces</span>
  {#if offered.length === 0}
    <span class="text-xs" style="color: var(--warn)">This device offers no {port.resource} on any face{device?.loaded === false ? " (not loaded)" : ""}.</span>
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

{#if port.resource === "item"}
  <div class="flex flex-col gap-1">
    <span class="text-xs font-medium muted">Slots</span>
    <div class="flex items-center gap-2 text-sm">
      <input
        type="number"
        min="0"
        class="w-20"
        placeholder="first"
        value={port.slots?.first ?? ""}
        {disabled}
        onchange={(e) => setSlots(e.currentTarget.value, String(port.slots?.last ?? ""))}
      />
      to
      <input
        type="number"
        min="0"
        class="w-20"
        placeholder="last"
        value={port.slots?.last ?? ""}
        {disabled}
        onchange={(e) => setSlots(String(port.slots?.first ?? ""), e.currentTarget.value)}
      />
    </div>
    <span class="text-xs muted">Leave empty for all slots.</span>
  </div>
{/if}
