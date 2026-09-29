<script lang="ts">
  import { app, deviceName } from "../../lib/state.svelte";
  import { REDSTONE_MODE_LABEL } from "../../lib/kinds";
  import { SIDES, type Face, type Redstone, type RedstoneMode, type RedstoneSource } from "../../lib/types";
  import DevicePicker from "./DevicePicker.svelte";
  import Field from "./Field.svelte";

  let {
    value,
    disabled = false,
    defaultLabel,
    onchange,
  }: {
    value: Redstone | undefined;
    disabled?: boolean;
    /** Where the "usual place" reads, e.g. "the controller" or "this device's connectors". */
    defaultLabel: string;
    onchange: (next: Redstone) => void;
  } = $props();

  const mode = $derived<RedstoneMode>(value?.mode ?? "ignored");
  const source = $derived<RedstoneSource>(value?.source ?? "default");
  const devices = $derived(app.snapshot?.devices ?? []);
  const connectors = $derived(app.snapshot?.connectors ?? []);

  /** Names transmitted anywhere on the network, plus names seen live. */
  const signalNames = $derived.by(() => {
    const names = new Set<string>(Object.keys(app.networkSignals));
    for (const sheet of app.snapshot?.sheets ?? []) {
      for (const node of sheet.nodes ?? []) {
        if (node.settings.kind === "transmit" && node.settings.name) names.add(node.settings.name);
      }
    }
    return [...names].sort();
  });

  /** Only the fields the chosen source uses are kept, so the saved condition stays small. */
  function set(patch: Partial<Redstone>) {
    const next: Redstone = { mode, source, ...value, ...patch };
    const clean: Redstone = { mode: next.mode, source: next.source };
    if (next.source === "connector" && next.connector) clean.connector = next.connector;
    if (next.source === "face" && next.device) {
      clean.device = next.device;
      if (next.face) clean.face = next.face;
    }
    if (next.source === "signal") clean.signal = next.signal ?? "";
    onchange(next.mode === "ignored" ? { mode: "ignored" } : clean);
  }

  function connectorLabel(c: { id: string; name?: string; pos: { pos: number[] } }): string {
    return c.name || `Connector (${c.pos.pos.join(", ")})`;
  }

  const listId = `signals-${Math.random().toString(36).slice(2, 8)}`;
</script>

<div class="flex flex-col gap-2 rounded-md border p-2" style="border-color: var(--line)">
  <Field label="Redstone" hint="Runs only while this allows it (and its on/off button is on). Pulse: once per rising edge.">
    <select value={mode} {disabled} onchange={(e) => set({ mode: e.currentTarget.value as RedstoneMode })}>
      {#each Object.entries(REDSTONE_MODE_LABEL) as [key, label]}<option value={key}>{label}</option>{/each}
    </select>
  </Field>
  {#if mode !== "ignored"}
    <Field label="Read from">
      <select value={source} {disabled} onchange={(e) => set({ source: e.currentTarget.value as RedstoneSource })}>
        <option value="default">The usual place ({defaultLabel})</option>
        <option value="connector">A connector</option>
        <option value="face">A device face</option>
        <option value="signal">A network signal</option>
      </select>
    </Field>
    {#if source === "connector"}
      <Field label="Connector" hint="The strongest signal it receives from any side.">
        <select value={value?.connector ?? ""} {disabled} onchange={(e) => set({ connector: e.currentTarget.value })}>
          <option value="" disabled>Choose…</option>
          {#each connectors as c (c.id)}<option value={c.id}>{connectorLabel(c)}</option>{/each}
        </select>
      </Field>
    {:else if source === "face"}
      <Field label="Device">
        {#if value?.device}
          <DevicePicker {devices} value={value.device} {disabled} onchange={(d) => set({ device: d.pos })} />
        {:else}
          <select value="" {disabled} onchange={(e) => set({ device: devices[Number(e.currentTarget.value)]?.pos })}>
            <option value="" disabled>Choose…</option>
            {#each devices as d, i}<option value={i}>{deviceName(d)}</option>{/each}
          </select>
        {/if}
      </Field>
      <Field label="Face" hint="The signal the device gives out of that face; any face takes the strongest.">
        <select value={value?.face ?? ""} {disabled} onchange={(e) => set({ face: (e.currentTarget.value || undefined) as Face | undefined })}>
          <option value="">Any face</option>
          {#each SIDES as f}<option value={f}>{f}</option>{/each}
        </select>
      </Field>
    {:else if source === "signal"}
      <Field label="Signal name" hint="Transmitted by a Transmitter node on any sheet.">
        <input value={value?.signal ?? ""} {disabled} list={listId} placeholder="e.g. Furnaces full" onchange={(e) => set({ signal: e.currentTarget.value.trim() })} />
        <datalist id={listId}>{#each signalNames as name}<option value={name}></option>{/each}</datalist>
      </Field>
    {/if}
  {/if}
</div>
