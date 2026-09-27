<script lang="ts">
  import { Handle, Position, useUpdateNodeInternals } from "@xyflow/svelte";
  import type { NodeData } from "../../lib/graph";
  import { KIND_LABEL, RESOURCE_VAR, formatAmount, sideLabel, summary } from "../../lib/kinds";
  import { deviceName } from "../../lib/state.svelte";
  import DeviceIcon from "../DeviceIcon.svelte";

  let { id, data, selected }: { id: string; data: NodeData; selected?: boolean } = $props();
  const updateInternals = useUpdateNodeInternals();

  const HEADER = 46;
  const ROW = 24;

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
  class="rounded-lg border text-left"
  style="width: {kind === 'device' ? 272 : 224}px; background: var(--panel); border-color: {selected ? 'var(--accent)' : 'var(--line)'}; box-shadow: {selected
    ? '0 0 0 1px var(--accent)'
    : '0 1px 3px rgb(0 0 0 / 0.25)'}; opacity: {disabled ? 0.5 : 1}"
>
  <div class="flex items-center gap-2 rounded-t-lg px-2" style="height: {HEADER}px; border-bottom: 1px solid var(--line); border-left: 4px solid {color}">
    {#if data.device}<DeviceIcon item={data.device.icon} size={20} />{/if}
    <div class="min-w-0 flex-1">
      <div class="truncate font-medium" title={title}>{title}</div>
      <div class="text-xs muted">{KIND_LABEL[kind]}{"resource" in s ? ` · ${s.resource}` : ""}{offline ? ` · ${data.device?.state}` : ""}{disabled ? " · off" : ""}</div>
    </div>
    {#if data.rate > 0}
      <span class="rounded px-1.5 text-xs font-medium tabular-nums" style="background: color-mix(in srgb, {color} 20%, transparent)"
        >{formatAmount(data.rate, data.resource)}/s</span
      >
    {/if}
  </div>
  {#if kind === "device"}
    <div class="grid grid-cols-2 text-xs" style="height: {rows * ROW}px">
      <div class="flex flex-col">
        {#each inputs as input}
          <div class="truncate pl-3" style="height: {ROW}px; line-height: {ROW}px" title={sideLabel(input)}>▸ {sideLabel(input)}</div>
        {/each}
      </div>
      <div class="flex flex-col text-right">
        {#each outputs as output}
          <div class="truncate pr-3" style="height: {ROW}px; line-height: {ROW}px" title={sideLabel(output)}>{sideLabel(output)} ▸</div>
        {/each}
      </div>
    </div>
  {:else}
    <div class="truncate px-2 py-1 text-xs muted" title={summary(data.node)}>{summary(data.node)}</div>
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
  {#if kind !== "source"}
    <Handle type="target" position={Position.Left} id="in" />
  {/if}
  {#if kind === "filter"}
    <Handle type="source" position={Position.Right} id="match" style="top: 35%; background: var(--good)" />
    <Handle type="source" position={Position.Right} id="reject" style="top: 75%; background: var(--bad)" />
  {:else if kind !== "sink"}
    <Handle type="source" position={Position.Right} id="out" style="background: {color}" />
  {/if}
{/if}
