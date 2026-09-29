<script lang="ts">
  import { app, edit, deviceName, isIdentified, toggleIdentify } from "../../lib/state.svelte";
  import { RESOURCE_LABEL } from "../../lib/kinds";
  import { FACES, posKey, type Device, type Face, type Resource } from "../../lib/types";
  import DeviceIcon from "../DeviceIcon.svelte";
  import Field from "./Field.svelte";

  let { device }: { device: Device } = $props();

  const canEdit = $derived(app.snapshot?.canEdit ?? false);

  function rename(name: string) {
    const value = name.trim() || null;
    if (value === (device.name ?? null)) return;
    edit([{ type: "renameDevice", device: device.pos, name: value }], [{ type: "renameDevice", device: device.pos, name: device.name ?? null }], "rename device");
  }

  function renameFace(face: Face, name: string) {
    const value = name.trim() || null;
    const before = device.faceNames[face] ?? null;
    if (value === before) return;
    edit([{ type: "renameFace", device: device.pos, face, name: value }], [{ type: "renameFace", device: device.pos, face, name: before }], "rename face");
  }

  function relink(key: string) {
    const target = app.snapshot?.devices.find((d) => posKey(d.pos) === key);
    if (!target || !confirm(`Move the names and every flow of "${deviceName(device)}" onto "${deviceName(target)}"?`)) return;
    app.selectedDevice = key;
    edit([{ type: "relinkDevice", from: device.pos, to: target.pos }]);
  }

  function deleteOrphan() {
    if (confirm(`Forget "${deviceName(device)}"? Its name and face names are lost.`)) {
      app.selectedDevice = null;
      edit([{ type: "deleteOrphan", device: device.pos }]);
    }
  }
</script>

<div class="flex flex-col gap-3">
  <div class="flex items-center gap-2">
    <DeviceIcon item={device.icon} size={32} />
    <div class="min-w-0 flex-1">
      <h2 class="truncate text-base font-semibold">{deviceName(device)}</h2>
      <p class="truncate text-xs muted">{device.block} · {device.pos.pos.join(", ")}</p>
    </div>
    <button
      class="btn shrink-0 text-sm"
      style={isIdentified({ kind: "device", device: device.pos }) ? "border-color: var(--warn); color: var(--warn)" : ""}
      disabled={!canEdit}
      title="Light it up in the world until turned off"
      onclick={() => toggleIdentify({ kind: "device", device: device.pos })}>ID</button
    >
  </div>

  {#if device.state === "active" && device.outOfRange}
    <p class="rounded-md border p-2 text-sm" style="border-color: var(--warn)">
      Out of range: only wireless connectors reach this device, and the controller's antenna doesn't reach them. Install a better antenna
      or run a cable to them.
    </p>
  {/if}
  {#if device.state !== "active"}
    <div class="rounded-md border p-2 text-sm" style="border-color: var(--warn)">
      {device.state === "orphaned" ? "No connector reaches this device any more." : "The block is gone."}
      It is kept because it has a name{canEdit ? "; forget it when you no longer need it." : "."}
      {#if canEdit}
        <div class="mt-2 flex flex-col gap-2">
          <label class="flex flex-col gap-1">
            <span class="text-xs muted">Moved or replaced it? Move its names and every flow onto the new block:</span>
            <select value="" onchange={(e) => relink(e.currentTarget.value)}>
              <option value="" disabled>Choose the new block…</option>
              {#each (app.snapshot?.devices ?? []).filter((d) => d.state === "active") as d (posKey(d.pos))}
                <option value={posKey(d.pos)}>{deviceName(d)}</option>
              {/each}
            </select>
          </label>
          <button class="btn btn-danger self-start text-sm" onclick={deleteOrphan}>Forget device</button>
        </div>
      {/if}
    </div>
  {/if}

  <Field label="Name">
    <input value={device.name ?? ""} placeholder={device.defaultName} disabled={!canEdit} onchange={(e) => rename(e.currentTarget.value)} />
  </Field>

  <div class="flex flex-col gap-1">
    <span class="text-xs font-medium muted">Face names</span>
    {#each FACES as face}
      <div class="grid grid-cols-[4.5rem_minmax(0,1fr)_auto] items-center gap-2 text-sm">
        <span class="muted">{face}</span>
        <input class="w-full min-w-0" value={device.faceNames[face] ?? ""} placeholder="—" disabled={!canEdit} onchange={(e) => renameFace(face, e.currentTarget.value)} />
        {#if face !== "unsided"}
          <button
            class="btn px-1.5 text-xs"
            style={isIdentified({ kind: "face", device: device.pos, face }) ? "border-color: var(--warn); color: var(--warn)" : ""}
            disabled={!canEdit}
            title="Light this face up in the world until turned off"
            onclick={() => toggleIdentify({ kind: "face", device: device.pos, face })}>ID</button
          >
        {:else}<span></span>{/if}
      </div>
    {/each}
  </div>

  {#if device.resources}
    <div class="flex flex-col gap-1 text-sm">
      <span class="text-xs font-medium muted">Offers</span>
      {#each Object.entries(device.resources) as [resource, faces]}
        {#if faces?.length}
          <div><b>{RESOURCE_LABEL[resource as Resource]}</b> <span class="muted">on {faces.join(", ")}</span></div>
        {/if}
      {/each}
    </div>
  {/if}
</div>
