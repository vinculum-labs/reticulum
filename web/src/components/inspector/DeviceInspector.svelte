<script lang="ts">
  import { app, deviceUses, edit, deviceName, openSheet, showNode } from "../../lib/state.svelte";
  import { RESOURCE_LABEL, NAME_LIMIT } from "../../lib/kinds";
  import { FACES, posKey, type Device, type Face, type Resource } from "../../lib/types";
  import { confirmDialog } from "../../lib/dialog.svelte";
  import DeviceIcon from "../DeviceIcon.svelte";
  import IdentifyToggle from "../controls/IdentifyToggle.svelte";
  import TextField from "../controls/TextField.svelte";
  import DevicePicker from "./DevicePicker.svelte";
  import Field from "./Field.svelte";
  import InspectorPanel from "./InspectorPanel.svelte";

  let { device }: { device: Device } = $props();

  const canEdit = $derived(app.snapshot?.canEdit ?? false);
  const uses = $derived(app.snapshot ? (deviceUses(app.snapshot).get(posKey(device.pos)) ?? []) : []);
  const home = $derived(uses.find((u) => u.home));
  const readBy = $derived(uses.filter((u) => !u.home));
  // Moving onto a block something already uses (a node, a slot, a chart) would give it two: offered only free ones.
  const activeDevices = $derived.by(() => {
    const all = app.snapshot ? deviceUses(app.snapshot) : new Map();
    return (app.snapshot?.devices ?? []).filter((d) => d.state === "active" && !(all.get(posKey(d.pos)) ?? []).length);
  });

  function rename(name: string) {
    const value = name || null;
    edit([{ type: "renameDevice", device: device.pos, name: value }], [{ type: "renameDevice", device: device.pos, name: device.name ?? null }], "rename device");
  }

  function renameFace(face: Face, name: string) {
    const value = name || null;
    const before = device.faceNames[face] ?? null;
    edit([{ type: "renameFace", device: device.pos, face, name: value }], [{ type: "renameFace", device: device.pos, face, name: before }], "rename face");
  }

  // Each takes the device's position first: changing the selection closes this panel, and its props with it.
  async function relink(target: Device) {
    const network = app.current;
    const from = device.pos;
    const ok = await confirmDialog({
      title: `Move “${deviceName(device)}” onto “${deviceName(target)}”?`,
      message: "Its names and every flow that uses it move to the other device.",
      confirm: "Move",
    });
    if (!ok || app.current !== network) return;
    app.selectedDevice = posKey(target.pos);
    edit([{ type: "relinkDevice", from, to: target.pos }]);
  }

  async function deleteOrphan() {
    const network = app.current;
    const pos = device.pos;
    const ok = await confirmDialog({
      title: `Forget “${deviceName(device)}”?`,
      message: "Its name and face names are lost.",
      confirm: "Forget",
      danger: true,
    });
    if (ok && app.current === network) {
      app.selectedDevice = null;
      edit([{ type: "deleteOrphan", device: pos }]);
    }
  }
</script>

<InspectorPanel
  title={deviceName(device)}
  subtitle={`${device.block} · ${device.pos.pos.join(", ")}`}
  onback={app.selectedNodes.length || app.selectedEdges.length ? () => (app.selectedDevice = null) : undefined}
  backLabel="Back to the selection"
>
  {#snippet icon()}<DeviceIcon item={device.icon} size={32} />{/snippet}
  {#snippet actions()}<IdentifyToggle target={{ kind: "device", device: device.pos }} />{/snippet}

  {#if device.state === "active" && device.outOfRange}
    <p class="box box-warn text-sm" role="status">
      {#if app.snapshot?.antenna}
        Out of range: no cable joins its connector to the controller, and it is beyond the antenna's range. Run a cable to it or install a
        better antenna.
      {:else}
        Unreachable: no cable joins its connector to the controller, and the controller has no antenna. Run a cable to it or install an
        antenna.
      {/if}
    </p>
  {/if}
  {#if device.state !== "active"}
    <div class="box box-warn flex flex-col gap-2 text-sm" role="status">
      <span>
        {device.state === "orphaned" ? "No connector reaches this device any more." : "The block is gone."}
        It is kept because it has a name or flows use it{canEdit ? " — forget it when you no longer need it." : "."}
      </span>
      {#if canEdit}
        <Field label="Moved or replaced it? Move its names and every flow onto the new block:">
          <DevicePicker devices={activeDevices} placeholder="Choose the new block…" onchange={relink} />
        </Field>
        <button class="btn btn-danger self-start text-sm" onclick={deleteOrphan}>Forget device</button>
      {/if}
    </div>
  {/if}

  <div class="flex flex-col gap-1 text-sm">
    <span class="field-label">Lives in</span>
    {#if home}
      <button class="list-row self-start rounded px-1 text-left text-accent" onclick={() => showNode(home.sheetId!, home.nodeId!)}>{home.label}</button>
    {:else}
      <span class="muted">No sheet yet. Drag it from the list onto the canvas.</span>
    {/if}
  </div>
  {#if readBy.length}
    <div class="flex flex-col gap-1 text-sm">
      <span class="field-label">Also used by</span>
      {#each readBy as use (use.label + (use.sheetId ?? "") + (use.nodeId ?? ""))}
        {#if use.sheetId && use.nodeId}
          <button class="list-row self-start rounded px-1 text-left text-accent" onclick={() => showNode(use.sheetId!, use.nodeId!)}>{use.label}</button>
        {:else if use.sheetId}
          <button class="list-row self-start rounded px-1 text-left text-accent" onclick={() => openSheet(use.sheetId!)}>{use.label}</button>
        {:else}
          <span class="px-1">{use.label}</span>
        {/if}
      {/each}
    </div>
  {/if}

  <Field label="Name">
    <TextField maxlength={NAME_LIMIT} value={device.name} placeholder={device.defaultName} disabled={!canEdit} oncommit={rename} />
  </Field>

  <fieldset class="flex flex-col gap-1">
    <legend class="field-label mb-1">Face names</legend>
    {#each FACES as face}
      <div class="grid grid-cols-[4.5rem_minmax(0,1fr)_auto] items-center gap-2 text-sm">
        <span class="muted">{face}</span>
        <TextField maxlength={NAME_LIMIT} class="w-full min-w-0" value={device.faceNames[face]} placeholder="—" disabled={!canEdit} title={`Name of the ${face} face`}
          oncommit={(v) => renameFace(face, v)} />
        {#if face !== "unsided"}
          <IdentifyToggle target={{ kind: "face", device: device.pos, face }} title="Light this face up in the world until turned off" />
        {:else}
          <!-- Unsided is the block taken as a whole: its ID lights the whole block, as the device's own does. -->
          <IdentifyToggle target={{ kind: "device", device: device.pos }} title="Light the whole block up in the world until turned off" />
        {/if}
      </div>
    {/each}
  </fieldset>

  {#if device.resources}
    <div class="flex flex-col gap-1 text-sm">
      <span class="field-label">Offers</span>
      {#each Object.entries(device.resources) as [resource, faces]}
        {#if faces?.length}
          <div><b>{RESOURCE_LABEL[resource as Resource]}</b> <span class="muted">on {faces.join(", ")}</span></div>
        {/if}
      {/each}
    </div>
  {/if}
</InspectorPanel>
