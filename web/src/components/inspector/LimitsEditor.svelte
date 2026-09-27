<script lang="ts">
  import type { Limit, Resource, Scope } from "../../lib/types";
  import { RESOURCE_UNIT } from "../../lib/kinds";
  import FilterField from "./FilterField.svelte";

  let {
    limits,
    keep,
    resource,
    disabled,
    onchange,
  }: { limits: Limit[]; keep: boolean; resource: Resource; disabled: boolean; onchange: (limits: Limit[]) => void } = $props();

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

<div class="flex flex-col gap-2">
  <span class="text-xs font-medium muted">{keep ? "Keep rules (leave at least)" : "Caps (stop inserting at)"}</span>
  {#each limits as limit, i}
    <div class="flex flex-col gap-1 rounded-md border p-2" style="border-color: var(--line)">
      <div class="flex items-center gap-1 text-sm">
        <input
          type="number"
          min="0"
          class="w-20"
          value={limit.amount}
          {disabled}
          onchange={(e) => set(i, { amount: Math.max(0, Number(e.currentTarget.value) || 0) })}
        />
        <span class="muted">{unit}</span>
        {#if resource !== "energy"}
          <select value={limit.scope} {disabled} onchange={(e) => set(i, { scope: e.currentTarget.value as Scope })}>
            {#each scopes as s}<option value={s.value}>{s.label}</option>{/each}
          </select>
        {/if}
        <button class="ml-auto text-sm muted" {disabled} onclick={() => onchange(limits.filter((_, j) => j !== i))} title="Remove">✕</button>
      </div>
      {#if resource !== "energy"}
        <FilterField value={limit.filter ?? ""} {disabled} placeholder="applies to everything" onchange={(v) => set(i, { filter: v })} />
      {/if}
    </div>
  {/each}
  <button class="btn self-start text-sm" {disabled} onclick={() => onchange([...limits, { scope: "per_type", amount: keep ? 16 : 64, filter: "" }])}>
    + {keep ? "Keep rule" : "Cap"}
  </button>
</div>
