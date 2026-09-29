<script lang="ts">
  import { Handle, Position, useUpdateNodeInternals } from "@xyflow/svelte";
  import type { NodeData } from "../../lib/graph";
  import {
    KIND_LABEL,
    RESOURCE_VAR,
    STATUS_LABEL,
    formatAgo,
    formatAverage,
    formatRate,
    gated,
    meUsageLabel,
    isSignal,
    redstoneSummary,
    shortLabel,
    sideLabel,
    signalInputs,
    signalOutput,
    summary,
  } from "../../lib/kinds";
  import { app, deviceName } from "../../lib/state.svelte";
  import DeviceIcon from "../DeviceIcon.svelte";

  let { id, data, selected }: { id: string; data: NodeData; selected?: boolean } = $props();
  const updateInternals = useUpdateNodeInternals();

  const BASE_HEADER = 34;
  const ROW = 18;

  const s = $derived(data.node.settings);
  const kind = $derived(s.kind);
  const signal = $derived(isSignal(s.kind));
  const color = $derived(
    signal ? "var(--c-signal)" : data.resource ? RESOURCE_VAR[data.resource] : "var(--muted)",
  );
  const title = $derived(
    data.node.label ||
      (data.device
        ? deviceName(data.device)
        : kind === "subsheet"
          ? (data.subsheet?.sheet?.name ?? "Missing sub-sheet")
          : (s.kind === "sheet_input" || s.kind === "sheet_output") && s.name
            ? s.name
            : KIND_LABEL[kind]),
  );
  const offline = $derived(data.device && (data.device.state !== "active" || data.device.outOfRange));
  const disabled = $derived(data.node.enabled === false);

  /** Labeled pin rows: a device's inputs and outputs, or a sub-sheet's pins. */
  const leftRows = $derived<{ label: string; title: string; color: string }[]>(
    s.kind === "device"
      ? (s.inputs ?? []).map((x, i) => ({ label: pinLabel(x, `${id}/in/${i}`), title: sideLabel(x), color: RESOURCE_VAR[x.resource] }))
      : kind === "subsheet"
        ? (data.subsheet?.inputs ?? []).map((name) => ({ label: name, title: name, color }))
        : signal
          ? signalInputs(kind).map((name) => ({ label: name, title: name, color }))
          : [],
  );
  const rightRows = $derived<{ label: string; title: string; color: string }[]>(
    s.kind === "device"
      ? (s.outputs ?? []).map((x, i) => ({ label: pinLabel(x, `${id}/out/${i}`), title: sideLabel(x), color: RESOURCE_VAR[x.resource] }))
      : kind === "subsheet"
        ? (data.subsheet?.outputs ?? []).map((name) => ({ label: name, title: name, color }))
        : signal && signalOutput(kind)
          ? [{ label: "out", title: "Output", color }]
          : [],
  );
  const pinned = $derived(kind === "device" || kind === "subsheet" || signal);
  const redstone = $derived(redstoneSummary(data.node.redstone));
  const HEADER = $derived(BASE_HEADER + (redstone ? 13 : 0));
  const rows = $derived(Math.max(leftRows.length, rightRows.length, 1));
  const idleTitle = $derived(
    [
      data.fiveMinutes > 0 ? `Average over 5 min: ${formatAverage(data.fiveMinutes, data.resource, app.rateUnit)}` : "Nothing moved in the last 5 min",
      data.ticksSinceMoved !== undefined ? `Last moved ${formatAgo(data.ticksSinceMoved)}` : "Never moved",
      data.status ? `Last attempt: ${STATUS_LABEL[data.status] ?? data.status}` : "",
    ]
      .filter(Boolean)
      .join("\n"),
  );

  /** A pin's label, with its live value for redstone ("redstone 15") and ME ("ME 1 ch · 4/8") pins. */
  function pinLabel(side: { resource: import("../../lib/types").Resource; faces?: string[] }, key: string): string {
    if (side.resource === "redstone") return `${shortLabel(side)} ${app.signals[key] ?? 0}`;
    if (side.resource === "me") return `${shortLabel(side)} ${meUsageLabel(app.me[key])}`;
    return shortLabel(side);
  }

  // Color says what a pin carries; shape says which way: inputs are hollow rings, outputs filled dots.
  const inPin = (c: string) => `background: var(--panel); border: 2px solid ${c}`;
  const outPin = (c: string) => `background: ${c}; border: 2px solid ${c}`;

  // New or removed handles must be measured again, or wires attach to stale positions.
  $effect(() => {
    void leftRows.length;
    void rightRows.length;
    void HEADER;
    updateInternals(id);
  });
</script>

<div
  class="rounded-md border text-left text-xs"
  style="width: max-content; min-width: {pinned ? 140 : 110}px; max-width: {pinned ? 220 : 180}px; background: var(--panel); border-color: {selected
    ? 'var(--accent)'
    : 'var(--line)'}; border-style: {kind === 'subsheet' ? 'double' : 'solid'}; border-width: {kind === 'subsheet' ? 3 : 1}px; box-shadow: {selected
    ? '0 0 0 1px var(--accent)'
    : '0 1px 3px rgb(0 0 0 / 0.25)'}; opacity: {disabled ? 0.5 : 1}"
>
  <div class="flex items-center gap-1.5 rounded-t-md px-1.5" style="height: {HEADER}px; border-bottom: 1px solid var(--line); border-left: 3px solid {color}">
    {#if data.device}<DeviceIcon item={data.device.icon} size={16} />{/if}
    <div class="min-w-0 flex-1 leading-tight">
      <div class="truncate font-medium" style="font-size: 12px" title={title}>{title}</div>
      <div class="truncate muted" style="font-size: 10px" title={signal ? summary(data.node, app.rateUnit) : idleTitle}>
        {#if signal}
          <span class="tabular-nums" style="color: {(data.signal ?? 0) > 0 ? color : 'var(--muted)'}">{data.signal ?? 0}</span>
          · {summary(data.node, app.rateUnit)}
        {:else if data.rate > 0}
          <span class="tabular-nums" style="color: {color}">{formatRate(data.rate, data.resource, app.rateUnit)}</span>
        {:else if data.status}
          <span style="color: var(--warn)">{STATUS_LABEL[data.status] ?? data.status}</span>
        {:else if data.fiveMinutes > 0}
          <span class="tabular-nums">avg {formatAverage(data.fiveMinutes, data.resource, app.rateUnit)}</span>
        {:else}
          {KIND_LABEL[kind]}
        {/if}{offline ? ` · ${data.device?.outOfRange ? "out of range" : data.device?.state}` : ""}{disabled ? " · off" : ""}
      </div>
      {#if redstone}<div class="truncate" style="font-size: 10px; color: var(--c-signal)" title={redstone}>⚡ {redstone}</div>{/if}
    </div>
  </div>
  {#if pinned}
    <div class="grid grid-cols-2 gap-2" style="height: {rows * ROW}px; font-size: 10px">
      <div class="flex flex-col">
        {#each leftRows as row}
          <div class="truncate pl-2" style="height: {ROW}px; line-height: {ROW}px" title={row.title}>{row.label}</div>
        {/each}
      </div>
      <div class="flex flex-col text-right">
        {#each rightRows as row}
          <div class="truncate pr-2" style="height: {ROW}px; line-height: {ROW}px" title={row.title}>{row.label}</div>
        {/each}
      </div>
    </div>
  {:else if kind === "filter"}
    <div class="truncate px-1.5 pt-0.5 muted" style="font-size: 10px" title={summary(data.node, app.rateUnit)}>{summary(data.node, app.rateUnit)}</div>
    <div class="flex flex-col text-right" style="font-size: 10px">
      <div class="pr-2" style="height: {ROW}px; line-height: {ROW}px; color: var(--good)">match</div>
      <div class="pr-2" style="height: {ROW}px; line-height: {ROW}px; color: var(--bad)">other</div>
    </div>
  {:else if kind !== "sheet_input" && kind !== "sheet_output"}
    <div class="truncate px-1.5 py-0.5 muted" style="font-size: 10px" title={summary(data.node, app.rateUnit)}>{summary(data.node, app.rateUnit)}</div>
  {/if}
</div>

{#if gated(kind)}
  <!-- A signal wire into the gate switches the node on (above 0) and off. -->
  <Handle type="target" position={Position.Top} id="gate" title="Gate: a signal here switches this node on and off" style={inPin("var(--c-signal)")} />
{/if}
{#if pinned}
  {#each leftRows as row, i}
    <Handle type="target" position={Position.Left} id={`in:${i}`} style="top: {HEADER + ROW * i + ROW / 2}px; {inPin(row.color)}" />
  {/each}
  {#each rightRows as row, i}
    <Handle type="source" position={Position.Right} id={`out:${i}`} style="top: {HEADER + ROW * i + ROW / 2}px; {outPin(row.color)}" />
  {/each}
{:else}
  {#if kind !== "sheet_input"}
    <Handle type="target" position={Position.Left} id="in" style={inPin(color)} />
  {/if}
  {#if kind === "filter"}
    <!-- Aligned with the "match" and "other" rows under the summary line (16px). -->
    <Handle type="source" position={Position.Right} id="match" style="top: {HEADER + 16 + ROW / 2}px; {outPin('var(--good)')}" />
    <Handle type="source" position={Position.Right} id="reject" style="top: {HEADER + 16 + ROW + ROW / 2}px; {outPin('var(--bad)')}" />
  {:else if kind !== "sheet_output"}
    <Handle type="source" position={Position.Right} id="out" style={outPin(color)} />
  {/if}
{/if}
