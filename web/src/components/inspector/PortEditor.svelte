<script lang="ts">
  import { app, deviceName } from "../../lib/state.svelte";
  import { posKey, type PortFields } from "../../lib/types";
  import Field from "./Field.svelte";
  import SideEditor from "./SideEditor.svelte";

  let { port, disabled, onchange }: { port: PortFields; disabled: boolean; onchange: (port: PortFields) => void } = $props();

  const devices = $derived(app.snapshot?.devices ?? []);
  const deviceKey = $derived(posKey(port.device));
  const device = $derived(devices.find((d) => posKey(d.pos) === deviceKey));
  const candidates = $derived(devices.filter((d) => d.resources?.[port.resource]?.length || posKey(d.pos) === deviceKey));

  function setDevice(key: string) {
    const d = devices.find((x) => posKey(x.pos) === key);
    if (d) onchange({ ...port, device: d.pos, faces: [] });
  }
</script>

<Field label="Device">
  <select value={deviceKey} {disabled} onchange={(e) => setDevice(e.currentTarget.value)}>
    {#each candidates as d (posKey(d.pos))}
      <option value={posKey(d.pos)}>{deviceName(d)}{d.state !== "active" ? ` (${d.state})` : ""}</option>
    {/each}
  </select>
</Field>

<SideEditor side={port} {device} {disabled} onchange={(side) => onchange({ ...port, ...side })} />
