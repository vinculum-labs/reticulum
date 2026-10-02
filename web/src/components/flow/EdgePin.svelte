<script lang="ts">
  import TriangleAlert from "@lucide/svelte/icons/triangle-alert";
  import { Handle, Position } from "@xyflow/svelte";
  import type { NodeData } from "../../lib/graph";
  import { PIN_H, PIN_W } from "../../lib/graph";
  import { RESOURCE_LABEL, RESOURCE_VAR } from "../../lib/kinds";

  /**
   * A sheet's pin, drawn on the edge of its frame: inputs on the left with their wire leaving inwards (right),
   * outputs on the right with their wire coming in from the left. Drag it up or down to reorder; select it to name it.
   */
  let { data, selected }: { data: NodeData; selected?: boolean } = $props();

  const s = $derived(data.node.settings);
  const input = $derived(s.kind === "sheet_input");
  const resource = $derived(s.kind === "sheet_input" || s.kind === "sheet_output" ? s.resource : undefined);
  const name = $derived((s.kind === "sheet_input" || s.kind === "sheet_output") && s.name ? s.name : input ? "in" : "out");
  const color = $derived(resource ? RESOURCE_VAR[resource] : "var(--muted)");
  const title = $derived(`${input ? "Input" : "Output"} pin “${name}”: ${resource ? RESOURCE_LABEL[resource] : "carries anything"}`);
</script>

<div
  class="flex items-center gap-1 rounded-full border px-2 text-xs"
  style="width: {PIN_W}px; height: {PIN_H}px; background: var(--panel); border-color: {selected ? 'var(--accent)' : data.problem ? 'var(--bad)' : color};
    border-width: {selected ? 2 : 1}px; flex-direction: {input ? 'row' : 'row-reverse'}"
  title={data.problem ? `${title}\n${data.problem}` : title}
>
  <span class="min-w-0 flex-1 truncate" style="text-align: {input ? 'left' : 'right'}">{name}</span>
  {#if data.signal !== undefined && resource === "redstone"}<span class="tabular-nums muted">{data.signal}</span>{/if}
  {#if data.problem}<span class="shrink-0 text-bad"><TriangleAlert size={11} /></span>{/if}
</div>

{#if input}
  <Handle type="source" position={Position.Right} id="out" style="background: {color}; border: 2px solid {color}" />
{:else}
  <Handle type="target" position={Position.Left} id="in" style="background: var(--panel); border: 2px solid {color}" />
{/if}
