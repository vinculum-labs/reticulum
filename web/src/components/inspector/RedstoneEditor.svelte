<script lang="ts">
  import { app, signalNames } from "../../lib/state.svelte";
  import { REDSTONE_MODE_LABEL } from "../../lib/kinds";
  import { SIDES, type Face, type Redstone, type RedstoneMode, type RedstoneSource } from "../../lib/types";
  import TextField from "../controls/TextField.svelte";
  import Select from "../controls/Select.svelte";
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

  const names = $derived(signalNames());

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

</script>

<div class="box flex flex-col gap-2">
  <Field label="Redstone" hint="Runs only while this allows it (and its on/off button is on). Pulse: once per rising edge.">
    <Select
      value={mode}
      {disabled}
      options={Object.entries(REDSTONE_MODE_LABEL).map(([key, label]) => ({ value: key as RedstoneMode, label }))}
      onchange={(m) => set({ mode: m })}
    />
  </Field>
  {#if mode !== "ignored"}
    <Field label="Read from">
      <Select
        value={source}
        {disabled}
        options={[
          { value: "default" as RedstoneSource, label: `The usual place (${defaultLabel})` },
          { value: "connector" as RedstoneSource, label: "A connector" },
          { value: "face" as RedstoneSource, label: "A device face" },
          { value: "signal" as RedstoneSource, label: "A network signal" },
        ]}
        onchange={(src) => set({ source: src })}
      />
    </Field>
    {#if source === "connector"}
      <Field label="Connector" hint="The strongest signal it receives from any side.">
        <Select
          value={value?.connector}
          {disabled}
          placeholder="Choose…"
          options={connectors.map((c) => ({ value: c.id, label: connectorLabel(c) }))}
          onchange={(connector) => set({ connector })}
        />
      </Field>
    {:else if source === "face"}
      <Field label="Device">
        <DevicePicker {devices} value={value?.device} {disabled} onchange={(d) => set({ device: d.pos })} />
      </Field>
      <Field label="Face" hint="The signal the device gives out of that face; any face takes the strongest.">
        <Select
          value={value?.face ?? ""}
          {disabled}
          options={[{ value: "", label: "Any face" }, ...SIDES.map((f) => ({ value: f as string, label: f }))]}
          onchange={(f) => set({ face: (f || undefined) as Face | undefined })}
        />
      </Field>
    {:else if source === "signal"}
      <Field label="Signal name" hint="Transmitted by a Transmitter node on any sheet.">
        <TextField value={value?.signal} {disabled} suggestions={names} placeholder="e.g. Furnaces full" oncommit={(v) => set({ signal: v })} />
      </Field>
    {/if}
  {/if}
</div>
