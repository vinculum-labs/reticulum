<script lang="ts">
  import { Handle, Position, useUpdateNodeInternals } from "@xyflow/svelte";
  import type { NodeData } from "../../lib/graph";
  import { KIND_LABEL, RESOURCE_VAR, formatAmount, shortLabel, sideLabel, summary } from "../../lib/kinds";
  import { deviceName } from "../../lib/state.svelte";
  import DeviceIcon from "../DeviceIcon.svelte";

  let { id, data, selected }: { id: string; data: NodeData; selected?: boolean } = $props();
  const updateInternals = useUpdateNodeInternals();

  const HEADER = 34;
  const ROW = 18;

  const s = $derived(data.node.settings);
  const kind = $derived(s.kind);
  const color = $derived(data.resource ? RESOURCE_VAR[data.resource] : "var(--muted)");
  const title = $derived(data.node.label || (data.device ? deviceName(data.device) : KIND_LABEL[kind]));
  const offline = $derived(data.device && data.device.state !== "active");
  const disabled = $derived(data.node.enabled === false);
  const inputs = $derived(s.kind === "device" ? (s.inputs ?? []) : []);
  const outputs = $derived(s.kind === "device" ? (s.outputs ?? []) : []);
  const rows = $derived(Math.max(inputs.length, outputs.length, 1));

  // New or removed handles must be measured again, or wires attach to stale positions.
  $effect(() => {
    void inputs.length;
    void outputs.length;
    updateInternals(id);
  });
</script>

<div
  class="rounded-md border text-left text-xs"
  style="width: max-content; min-width: {kind === 'device' ? 140 : 110}px; max-width: {kind === 'device' ? 220 : 180}px; background: var(--panel); border-color: {selected ? 'var(--accent)' : 'var(--line)'}; box-shadow: {selected
    ? '0 0 0 1px var(--accent)'
    : '0 1px 3px rgb(0 0 0 / 0.25)'}; opacity: {disabled ? 0.5 : 1}"
>
  <div class="flex items-center gap-1.5 rounded-t-md px-1.5" style="height: {HEADER}px; border-bottom: 1px solid var(--line); border-left: 3px solid {color}">
    {#if data.device}<DeviceIcon item={data.device.icon} size={16} />{/if}
    <div class="min-w-0 flex-1 leading-tight">
      <div class="truncate font-medium" style="font-size: 12px" title={title}>{title}</div>
      <div class="truncate muted" style="font-size: 10px">
        {#if data.rate > 0}<span class="tabular-nums" style="color: {color}">{formatAmount(data.rate, data.resource)}/s</span>{:else}{KIND_LABEL[kind]}{/if}{offline
          ? ` · ${data.device?.state}`
          : ""}{disabled ? " · off" : ""}
      </div>
    </div>
  </div>
  {#if kind === "device"}
    <div class="grid grid-cols-2 gap-2" style="height: {rows * ROW}px; font-size: 10px">
      <div class="flex flex-col">
        {#each inputs as input}
          <div class="truncate pl-2" style="height: {ROW}px; line-height: {ROW}px" title={sideLabel(input)}>{shortLabel(input)}</div>
        {/each}
      </div>
      <div class="flex flex-col text-right">
        {#each outputs as output}
          <div class="truncate pr-2" style="height: {ROW}px; line-height: {ROW}px" title={sideLabel(output)}>{shortLabel(output)}</div>
        {/each}
      </div>
    </div>
  {:else if kind === "filter"}
    <div class="truncate px-1.5 pt-0.5 muted" style="font-size: 10px" title={summary(data.node)}>{summary(data.node)}</div>
    <div class="flex flex-col text-right" style="font-size: 10px">
      <div class="pr-2" style="height: {ROW}px; line-height: {ROW}px; color: var(--good)">match</div>
      <div class="pr-2" style="height: {ROW}px; line-height: {ROW}px; color: var(--bad)">other</div>
    </div>
  {:else}
    <div class="truncate px-1.5 py-0.5 muted" style="font-size: 10px" title={summary(data.node)}>{summary(data.node)}</div>
  {/if}
</div>

{#if kind === "device"}
  {#each inputs as input, i}
    <Handle type="target" position={Position.Left} id={`in:${i}`} style="top: {HEADER + ROW * i + ROW / 2}px; background: {RESOURCE_VAR[input.resource]}" />
  {/each}
  {#each outputs as output, i}
    <Handle type="source" position={Position.Right} id={`out:${i}`} style="top: {HEADER + ROW * i + ROW / 2}px; background: {RESOURCE_VAR[output.resource]}" />
  {/each}
{:else}
  <Handle type="target" position={Position.Left} id="in" />
  {#if kind === "filter"}
    <!-- Aligned with the "match" and "other" rows under the summary line (16px). -->
    <Handle type="source" position={Position.Right} id="match" style="top: {HEADER + 16 + ROW / 2}px; background: var(--good)" />
    <Handle type="source" position={Position.Right} id="reject" style="top: {HEADER + 16 + ROW + ROW / 2}px; background: var(--bad)" />
  {:else}
    <Handle type="source" position={Position.Right} id="out" style="background: {color}" />
  {/if}
{/if}
