<script lang="ts">
  import type { Resource } from "../../lib/types";
  import NumberField from "../controls/NumberField.svelte";
  import Segmented from "../controls/Segmented.svelte";
  import Field from "./Field.svelte";

  /** How much a source (or device output) takes per operation, how often, and its priority. */
  interface Amounts {
    resource: Resource;
    per_operation?: number;
    interval?: number;
    priority?: number;
  }

  let {
    settings,
    sheetInterval,
    disabled,
    onchange,
  }: { settings: Amounts; sheetInterval: number; disabled: boolean; onchange: (patch: Partial<Amounts>) => void } = $props();

  type Mode = "single" | "stack" | "custom";
  const mode = $derived<Mode>(!settings.per_operation ? "stack" : settings.per_operation === 1 ? "single" : "custom");
</script>

{#if settings.resource === "item"}
  <Field label="Per operation" group>
    <div class="flex flex-wrap items-center gap-2">
      <Segmented
        label="Per operation"
        value={mode}
        {disabled}
        options={[
          { value: "single", label: "One item" },
          { value: "stack", label: "One stack" },
          { value: "custom", label: "Custom" },
        ]}
        onchange={(v) => onchange({ per_operation: v === "stack" ? 0 : v === "single" ? 1 : 16 })}
      />
      {#if mode === "custom"}
        <NumberField value={settings.per_operation} min={1} max={1_000_000} {disabled} oncommit={(v) => onchange({ per_operation: v })} />
      {/if}
    </div>
  </Field>
  <Field label="Every" hint={`Ticks between operations; 0 uses the sheet's interval (${sheetInterval}).`}>
    <NumberField value={settings.interval ?? 0} min={0} max={72_000} width="md" {disabled} oncommit={(v) => onchange({ interval: v })} />
  </Field>
{/if}
<Field label="Priority" hint="Higher goes first when several outputs feed the same place.">
  <NumberField value={settings.priority ?? 0} min={-1000} max={1000} width="md" {disabled} oncommit={(v) => onchange({ priority: v })} />
</Field>
