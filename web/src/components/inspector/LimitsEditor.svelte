<script lang="ts">
  import type { GlobalPos, Limit, Resource, Scope } from "../../lib/types";
  import { RESOURCE_UNIT } from "../../lib/kinds";
  import NumberField from "../controls/NumberField.svelte";
  import RemoveButton from "../controls/RemoveButton.svelte";
  import Select from "../controls/Select.svelte";
  import FilterField from "./FilterField.svelte";
  import AddButton from "../controls/AddButton.svelte";

  let {
    limits,
    keep,
    resource,
    device,
    disabled,
    onchange,
  }: { limits: Limit[]; keep: boolean; resource: Resource; device?: GlobalPos; disabled: boolean; onchange: (limits: Limit[]) => void } = $props();

  const unit = $derived(RESOURCE_UNIT[resource]);
  const scopes: { value: Scope; label: string }[] = [
    { value: "per_type", label: "of each type" },
    { value: "total", label: "in total" },
    { value: "filter", label: "matching the filter, together" },
  ];

  function set(i: number, patch: Partial<Limit>) {
    onchange(limits.map((l, j) => (j === i ? { ...l, ...patch } : l)));
  }
</script>

<fieldset class="flex flex-col gap-2">
  <!-- The + on the heading's line, as a device's Inputs and Outputs have theirs. -->
  <div class="flex items-center justify-between">
    <legend class="field-label">{keep ? "Keep rules (leave at least)" : "Caps (stop inserting at)"}</legend>
    <AddButton inset label={keep ? "Add a keep rule" : "Add a cap"} {disabled} onclick={() => onchange([...limits, { scope: "per_type", amount: keep ? 16 : 64, filter: "" }])} />
  </div>
  {#each limits as limit, i}
    <div class="box flex flex-col gap-1">
      <div class="flex items-center gap-1 text-sm">
        <NumberField value={limit.amount} min={0} max={Number.MAX_SAFE_INTEGER} {disabled} oncommit={(v) => set(i, { amount: v ?? 0 })} />
        <span class="muted">{unit}</span>
        {#if resource !== "energy"}
          <Select value={limit.scope} {disabled} options={scopes} onchange={(scope) => set(i, { scope })} />
        {/if}
        <RemoveButton class="ml-auto" label={keep ? "Remove keep rule" : "Remove cap"} {disabled} onclick={() => onchange(limits.filter((_, j) => j !== i))} />
      </div>
      {#if resource !== "energy"}
        <FilterField
          value={limit.filter ?? ""}
          {disabled}
          placeholder="applies to everything"
          kind={resource === "fluid" || resource === "chemical" ? resource : "item"}
          devices={device ? [device] : []}
          onchange={(v) => set(i, { filter: v })} />
      {/if}
    </div>
  {/each}
</fieldset>
