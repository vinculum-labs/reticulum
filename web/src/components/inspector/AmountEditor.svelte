<script lang="ts">
  import type { Resource } from "../../lib/types";
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

  const mode = $derived(!settings.per_operation ? "stack" : settings.per_operation === 1 ? "single" : "custom");
</script>

{#if settings.resource === "item"}
  <Field label="Per operation">
    <div class="flex items-center gap-2">
      <select
        value={mode}
        {disabled}
        onchange={(e) => {
          const v = e.currentTarget.value;
          onchange({ per_operation: v === "stack" ? 0 : v === "single" ? 1 : 16 });
        }}
      >
        <option value="single">One item</option>
        <option value="stack">One stack</option>
        <option value="custom">Custom</option>
      </select>
      {#if mode === "custom"}
        <input type="number" min="1" class="w-20" value={settings.per_operation} {disabled}
          onchange={(e) => onchange({ per_operation: Math.max(1, Number(e.currentTarget.value) || 1) })} />
      {/if}
    </div>
  </Field>
  <Field label="Every" hint={`Ticks between operations; 0 uses the sheet's interval (${sheetInterval}).`}>
    <input type="number" min="0" class="w-24" value={settings.interval ?? 0} {disabled}
      onchange={(e) => onchange({ interval: Math.max(0, Number(e.currentTarget.value) || 0) })} />
  </Field>
{/if}
<Field label="Priority" hint="Higher goes first when several outputs feed the same place.">
  <input type="number" class="w-24" value={settings.priority ?? 0} {disabled} onchange={(e) => onchange({ priority: Number(e.currentTarget.value) || 0 })} />
</Field>
