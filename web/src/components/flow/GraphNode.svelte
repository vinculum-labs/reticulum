<script lang="ts">
  import { Handle, Position } from "@xyflow/svelte";
  import type { NodeData } from "../../lib/graph";
  import { KIND_LABEL, RESOURCE_VAR, formatAmount, summary } from "../../lib/kinds";
  import { deviceName } from "../../lib/state.svelte";
  import DeviceIcon from "../DeviceIcon.svelte";

  let { data, selected }: { data: NodeData; selected?: boolean } = $props();

  const s = $derived(data.node.settings);
  const kind = $derived(s.kind);
  const color = $derived(data.resource ? RESOURCE_VAR[data.resource] : "var(--muted)");
  const title = $derived(data.node.label || (data.device ? deviceName(data.device) : KIND_LABEL[kind]));
  const offline = $derived(data.device && data.device.state !== "active");
  const disabled = $derived(data.node.enabled === false);
</script>

<div
  class="w-56 rounded-lg border text-left"
  style="background: var(--panel); border-color: {selected ? 'var(--accent)' : 'var(--line)'}; box-shadow: {selected
    ? '0 0 0 1px var(--accent)'
    : '0 1px 3px rgb(0 0 0 / 0.25)'}; opacity: {disabled ? 0.5 : 1}"
>
  <div class="flex items-center gap-2 rounded-t-lg px-2 py-1.5" style="border-bottom: 1px solid var(--line); border-left: 4px solid {color}">
    {#if data.device}<DeviceIcon item={data.device.icon} size={20} />{/if}
    <div class="min-w-0 flex-1">
      <div class="truncate font-medium" title={title}>{title}</div>
      <div class="text-xs muted">{KIND_LABEL[kind]}{"resource" in s ? ` · ${s.resource}` : ""}</div>
    </div>
    {#if data.rate > 0}
      <span class="rounded px-1.5 text-xs font-medium tabular-nums" style="background: color-mix(in srgb, {color} 20%, transparent)"
        >{formatAmount(data.rate, data.resource)}/s</span
      >
    {/if}
  </div>
  <div class="truncate px-2 py-1 text-xs muted" title={summary(data.node)}>
    {#if offline}<span style="color: var(--warn)">{data.device?.state} · </span>{/if}{#if disabled}off · {/if}{summary(data.node)}
  </div>
</div>

{#if kind !== "source"}
  <Handle type="target" position={Position.Left} id="in" />
{/if}
{#if kind === "filter"}
  <Handle type="source" position={Position.Right} id="match" style="top: 35%; background: var(--good)" />
  <Handle type="source" position={Position.Right} id="reject" style="top: 75%; background: var(--bad)" />
{:else if kind !== "sink"}
  <Handle type="source" position={Position.Right} id="out" style="background: {color}" />
{/if}
