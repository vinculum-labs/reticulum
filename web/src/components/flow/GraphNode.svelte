<script lang="ts">
  import TriangleAlert from "@lucide/svelte/icons/triangle-alert";
  import Zap from "@lucide/svelte/icons/zap";
  import { Handle, Position, useUpdateNodeInternals } from "@xyflow/svelte";
  import type { HolderPin, NodeData } from "../../lib/graph";
  import {
    KIND_LABEL,
    RESOURCE_LABEL,
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
  import { KIND_ICON } from "../../lib/icons";
  import IdentifyBadge from "../IdentifyBadge.svelte";

  let { id, data, selected }: { id: string; data: NodeData; selected?: boolean } = $props();
  const updateInternals = useUpdateNodeInternals();

  const BASE_HEADER = 34;
  const ROW = 18;

  const s = $derived(data.node.settings);
  const kind = $derived(s.kind);
  const signal = $derived(isSignal(s.kind));
  /** A typed sheet pin shows what it carries; other nodes the resource flowing through them. */
  const pinType = $derived(s.kind === "sheet_input" || s.kind === "sheet_output" ? s.resource : undefined);
  const color = $derived(
    signal || pinType === "redstone"
      ? "var(--c-signal)"
      : pinType
        ? RESOURCE_VAR[pinType]
        : data.resource
          ? RESOURCE_VAR[data.resource]
          : "var(--muted)",
  );
  const holds = $derived(kind === "sheet" || kind === "subsheet");
  const title = $derived(
    data.node.label ||
      (data.device
        ? deviceName(data.device)
        : holds
          ? (data.holder?.sheet?.name ?? (kind === "sheet" ? "Missing sheet" : "Missing sub-sheet"))
          : (s.kind === "sheet_input" || s.kind === "sheet_output") && s.name
            ? s.name
            : KIND_LABEL[kind]),
  );
  const offline = $derived(data.device && (data.device.state !== "active" || data.device.outOfRange));
  const disabled = $derived(data.node.enabled === false);

  type Row = { label: string; title: string; color: string; handle: string };

  /** A sheet or sub-sheet's pin as a row: its handle is its slot, so wires stay on it whatever the order. */
  function holderRow(pin: HolderPin, input: boolean): Row {
    return {
      label: pin.name,
      title: `${pin.name}${pin.resource ? ` (${RESOURCE_LABEL[pin.resource]})` : ""}`,
      color: pin.resource ? RESOURCE_VAR[pin.resource] : "var(--muted)",
      handle: `${input ? "in" : "out"}:${pin.slot}`,
    };
  }

  /** Labeled pin rows: a device's inputs and outputs, or a sheet's (or sub-sheet's) pins. */
  const leftRows = $derived<Row[]>(
    s.kind === "device"
      ? (s.inputs ?? []).map((x, i) => ({ label: pinLabel(x, `${id}/in/${i}`), title: sideLabel(x), color: RESOURCE_VAR[x.resource], handle: `in:${i}` }))
      : holds
        ? (data.holder?.inputs ?? []).map((pin) => holderRow(pin, true))
        : signal
          ? signalInputs(kind).map((name, i) => ({ label: name, title: name, color, handle: `in:${i}` }))
          : [],
  );
  const rightRows = $derived<Row[]>(
    s.kind === "device"
      ? (s.outputs ?? []).map((x, i) => ({ label: pinLabel(x, `${id}/out/${i}`), title: sideLabel(x), color: RESOURCE_VAR[x.resource], handle: `out:${i}` }))
      : holds
        ? (data.holder?.outputs ?? []).map((pin) => holderRow(pin, false))
        : signal && signalOutput(kind)
          ? [{ label: "out", title: "Output", color, handle: "out:0" }]
          : [],
  );
  const pinned = $derived(kind === "device" || holds || signal);
  /**
   * The other cards' pins (filter, rate limit, splitter, kit, sheet pins), as labeled rows like the pinned cards: the
   * input on the left, the outputs on the right, each pin beside its label. Ids are the handles' (wires keep them).
   */
  const flowIn = $derived(kind !== "sheet_input");
  const flowOuts = $derived<{ id: string; label: string; color: string }[]>(
    kind === "filter"
      ? [
          { id: "match", label: "match", color: "var(--good)" },
          { id: "reject", label: "other", color: "var(--bad)" },
        ]
      : kind === "sheet_output"
        ? []
        : [{ id: "out", label: "out", color }],
  );
  const flowRows = $derived(Math.max(1, flowOuts.length));
  /** What a filter, rate limit, splitter or kit does, shown on the header's second line while it is idle. */
  const flowSummary = $derived(!pinned && kind !== "sheet_input" && kind !== "sheet_output" ? summary(data.node, app.rateUnit) : "");
  const redstone = $derived(redstoneSummary(data.node.redstone));
  const HEADER = $derived(BASE_HEADER + (redstone ? 13 : 0));
  const rows = $derived(Math.max(leftRows.length, rightRows.length, 1));
  /**
   * Hovering this card. "Last moved … ago" counts up every second on every node, so it is read only while its tooltip
   * can show: tied to it always, every card would redraw once a second on an idle network.
   */
  let hovered = $state(false);
  const idleTitle = $derived(
    [
      data.fiveMinutes > 0 ? `Average over 5 min: ${formatAverage(data.fiveMinutes, data.resource, app.rateUnit)}` : "Nothing moved in the last 5 min",
      !hovered ? "" : app.ticksSinceMoved[id] !== undefined ? `Last moved ${formatAgo(app.ticksSinceMoved[id])}` : "Never moved",
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
  // Re-measure the handles only when the pins or the header actually change. The row arrays are rebuilt with the
  // node's data (every metrics update), so depending on them directly re-measured every node — a forced layout each —
  // once a second.
  const pinLayout = $derived(`${leftRows.map((r) => r.handle).join()}|${rightRows.map((r) => r.handle).join()}:${HEADER}`);
  $effect(() => {
    void pinLayout;
    updateInternals(id);
  });

  // Picked from the sheet's errors: pulse a few times once the view has moved to it, to catch the eye.
  let card: HTMLDivElement | undefined = $state();
  $effect(() => {
    if (app.flashNode !== id || !card) return;
    const ring = "0 0 0 0 color-mix(in srgb, var(--bad) 80%, transparent)";
    const wide = "0 0 0 14px color-mix(in srgb, var(--bad) 0%, transparent)";
    const pulse = card.animate([{ boxShadow: ring }, { boxShadow: wide }], { duration: 700, delay: 300, iterations: 3, easing: "ease-out" });
    return () => pulse.cancel();
  });
</script>

<div
  bind:this={card}
  class="rounded-md border text-left text-xs"
  style="width: max-content; min-width: {pinned ? 140 : 110}px; max-width: {pinned ? 220 : 180}px; background: var(--panel); border-color: {selected
    ? 'var(--accent)'
    : data.problem
      ? 'var(--bad)'
      : 'var(--line)'}; border-style: {kind === 'subsheet' ? 'double' : 'solid'}; border-width: {kind === 'subsheet' ? 3 : kind === 'sheet' ? 2 : 1}px; box-shadow: {selected
    ? '0 0 0 1px var(--accent)'
    : data.problem
      ? '0 0 0 1px var(--bad)'
      : '0 1px 3px rgb(0 0 0 / 0.25)'}; opacity: {disabled ? 0.5 : 1}"
>
  <div class="group flex items-center gap-1.5 rounded-t-md px-1.5" style="height: {HEADER}px; border-bottom: 1px solid var(--line); border-left: 3px solid {color}">
    {#if data.device}
      <DeviceIcon item={data.device.icon} size={16} />
    {:else}
      <!-- Nodes without a block of their own get the toolbar's icon for their kind. -->
      {@const KindIcon = KIND_ICON[kind]}
      <span class="shrink-0" style="color: {color}"><KindIcon size={16} /></span>
    {/if}
    <div class="min-w-0 flex-1 leading-tight">
      <div class="truncate font-medium" style="font-size: 12px" title={title}>{title}</div>
      <div
        class="truncate muted"
        style="font-size: 10px"
        title={signal ? summary(data.node, app.rateUnit) : flowSummary ? `${flowSummary}
${idleTitle}` : idleTitle}
        role="note"
        onpointerenter={() => (hovered = true)}
        onpointerleave={() => (hovered = false)}
      >
        {#if signal}
          <span class="tabular-nums" style="color: {(data.signal ?? 0) > 0 ? color : 'var(--muted)'}">{data.signal ?? 0}</span>
          · {summary(data.node, app.rateUnit)}
        {:else if data.rate > 0}
          <span class="tabular-nums" style="color: {color}">{formatRate(data.rate, data.resource, app.rateUnit)}</span>
        {:else if data.status}
          <span class="text-warn">{STATUS_LABEL[data.status] ?? data.status}</span>
        {:else if flowSummary}
          {flowSummary}
        {:else if data.fiveMinutes > 0}
          <span class="tabular-nums">avg {formatAverage(data.fiveMinutes, data.resource, app.rateUnit)}</span>
        {:else}
          {KIND_LABEL[kind]}
        {/if}{offline ? ` · ${data.device?.outOfRange ? "out of range" : data.device?.state}` : ""}{disabled ? " · off" : ""}
      </div>
      {#if redstone}<div class="flex items-center gap-0.5 truncate text-signal" style="font-size: 10px" title={redstone}><Zap size={10} class="shrink-0" />{redstone}</div>{/if}
    </div>
    {#if data.device}<IdentifyBadge device={data.device} />{/if}
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
  {:else}
    <div class="grid grid-cols-2 gap-2" style="height: {flowRows * ROW}px; font-size: 10px">
      <div class="flex flex-col">
        {#if flowIn}<div class="pl-2" style="height: {ROW}px; line-height: {ROW}px">in</div>{/if}
      </div>
      <div class="flex flex-col text-right">
        {#each flowOuts as out (out.id)}
          <div class="pr-2" style="height: {ROW}px; line-height: {ROW}px; color: {out.color}">{out.label}</div>
        {/each}
      </div>
    </div>
  {/if}
</div>

{#if data.problem}
  <span class="problem-badge" title={data.problem} role="img" aria-label={`Error: ${data.problem}`}><TriangleAlert size={12} /></span>
{/if}

{#if gated(kind)}
  <!-- A signal wire into the gate switches the node on (above 0) and off. -->
  <!-- On the left, centered on the header: wires come in from the left like every other input (a pin on top had
       square wires drop into it through whatever sat above), yet it stays apart from the data pins below. -->
  <Handle
    type="target"
    position={Position.Left}
    id="gate"
    class={data.gateWired ? "gate-pin wired" : "gate-pin"}
    title="Gate: a signal here switches this node on and off"
    style="top: {HEADER / 2}px; {inPin('var(--c-signal)')}"
  />
{/if}
{#if pinned}
  {#each leftRows as row, i (row.handle)}
    <Handle type="target" position={Position.Left} id={row.handle} style="top: {HEADER + ROW * i + ROW / 2}px; {inPin(row.color)}" />
  {/each}
  {#each rightRows as row, i (row.handle)}
    <Handle type="source" position={Position.Right} id={row.handle} style="top: {HEADER + ROW * i + ROW / 2}px; {outPin(row.color)}" />
  {/each}
{:else}
  {#if flowIn}
    <Handle type="target" position={Position.Left} id="in" style="top: {HEADER + ROW / 2}px; {inPin(color)}" />
  {/if}
  {#each flowOuts as out, i (out.id)}
    <Handle type="source" position={Position.Right} id={out.id} style="top: {HEADER + ROW * i + ROW / 2}px; {outPin(out.color)}" />
  {/each}
{/if}

<style>
  /* On the card's corner, outside it, so a node with an error can be spotted among many. */
  .problem-badge {
    position: absolute;
    top: -9px;
    right: -9px;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 20px;
    height: 20px;
    border-radius: 50%;
    background: var(--bad);
    color: var(--panel);
    box-shadow: 0 0 0 2px var(--panel);
  }
  /* The gate is a diamond, so it can't be taken for an input (round pins). */
  :global(.svelte-flow__handle.gate-pin) {
    border-radius: 2px;
    transform: translate(-50%, -50%) rotate(45deg);
  }
  /* An unused gate pin stays out of sight until the card is hovered or a wire is being dragged to it. */
  :global(.svelte-flow__handle.gate-pin:not(.wired)) {
    opacity: 0;
    transition: opacity var(--reveal-time) ease-in-out;
  }
  :global(.svelte-flow__node:hover .svelte-flow__handle.gate-pin),
  :global(.wiring .svelte-flow__handle.gate-pin) {
    opacity: 1;
  }
</style>
