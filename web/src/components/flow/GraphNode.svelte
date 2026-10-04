<script lang="ts">
  import TriangleAlert from "@lucide/svelte/icons/triangle-alert";
  import Siren from "@lucide/svelte/icons/siren";
  import ChevronRight from "@lucide/svelte/icons/chevron-right";
  import ChevronDown from "@lucide/svelte/icons/chevron-down";
  import { Handle, Position, useUpdateNodeInternals } from "@xyflow/svelte";
  import { CARD_HEADER, CARD_ROW, PIN_KNOB, type HolderPin, type NodeData } from "../../lib/graph";
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
    shortLabel,
    sideLabel,
    signalInputs,
    onWhenUnwired,
    summary,
  } from "../../lib/kinds";
  import { app, instanceRun, deviceName, toggleFrame, type Layer } from "../../lib/state.svelte";
  import { unreachable } from "../../lib/status";
  import { blockName, liveKey } from "../../lib/blueprints";
  import DeviceIcon from "../DeviceIcon.svelte";
  import { KIND_ICON } from "../../lib/icons";
  import IdentifyBadge from "../IdentifyBadge.svelte";

  let { id, data, selected, width, height }: { id: string; data: NodeData; selected?: boolean; width?: number; height?: number } = $props();
  const updateInternals = useUpdateNodeInternals();

  const BASE_HEADER = CARD_HEADER;
  const ROW = CARD_ROW;

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
  const holds = $derived(kind === "sheet" || kind === "instance");
  /** A Level sensor reads a device: titled as a sensor, the device it reads named below. */
  const sensor = $derived(kind === "level");
  // A sheet node goes by its sheet's name and a device node by its device's (renamed with them); a slot or any
  // other node by its own name when set.
  const title = $derived(
    (kind !== "sheet" && !(data.device && !data.slot) && data.node.label) ||
      (sensor
        ? KIND_LABEL.level
        : data.slot && !data.device
        ? `${blockName(data.slot.block)} slot`
        : data.device
        ? deviceName(data.device)
        : holds
          ? (data.holder?.sheet?.name ?? (kind === "sheet" ? "Missing sheet" : "Missing blueprint"))
          : (s.kind === "sheet_input" || s.kind === "sheet_output") && s.name
            ? s.name
            : KIND_LABEL[kind]),
  );
  const offline = $derived(data.device && (data.device.state !== "active" || data.device.outOfRange));
  /** Alarms going off from this node (or, on a placed blueprint, from inside it). */
  const raised = $derived((app.networks.find((n) => n.id === app.current)?.alarms ?? []).filter((a) => a.node === data.node.id));
  const disabled = $derived(data.node.enabled === false);

  /** {@code layer}: the kind of wire it takes, for the canvas's layers (dimmed with its layer). */
  type Row = { label: string; title: string; color: string; handle: string; layer?: Layer | null };

  /** A sheet or blueprint's pin as a row: its handle is its slot, so wires stay on it whatever the order. */
  function holderRow(pin: HolderPin, input: boolean): Row {
    return {
      label: pin.name,
      title: `${pin.name}${pin.resource ? ` (${RESOURCE_LABEL[pin.resource]})` : ""}`,
      color: pin.resource ? RESOURCE_VAR[pin.resource] : "var(--muted)",
      handle: `${input ? "in" : "out"}:${pin.slot}`,
      layer: pin.resource ?? null,
    };
  }

  /** Labeled pin rows: a device's inputs and outputs, or a sheet's (or blueprint's) pins. */
  const leftRows = $derived<Row[]>(
    s.kind === "device"
      ? (s.inputs ?? []).map((x, i) => ({ label: pinLabel(x, `${id}/in/${i}`), title: sideLabel(x), color: RESOURCE_VAR[x.resource], handle: `in:${i}`, layer: x.resource }))
      : holds
        ? (data.holder?.inputs ?? []).map((pin) => holderRow(pin, true))
        : signal
          ? signalInputs(kind).map((name, i) => {
              // An input that counts as on while unwired says so, until something is wired into it.
              const always = onWhenUnwired(kind) === i && !data.wiredInputs?.includes(i);
              return {
                label: always ? `${name} · always` : name,
                title: always ? `${name}: nothing wired in, so always on` : name,
                color,
                handle: `in:${i}`,
                layer: "redstone" as Layer,
              };
            })
          : [],
  );
  const rightRows = $derived<Row[]>(
    s.kind === "device"
      ? (s.outputs ?? []).map((x, i) => ({ label: pinLabel(x, `${id}/out/${i}`), title: sideLabel(x), color: RESOURCE_VAR[x.resource], handle: `out:${i}`, layer: x.resource }))
      : holds
        ? (data.holder?.outputs ?? []).map((pin) => holderRow(pin, false))
        : s.kind === "sequencer"
          ? [
              { label: "step", title: "The step playing (1 on; 0 once stopped)", color, handle: "out:0", layer: "redstone" as Layer },
              ...(s.tracks ?? []).map((t, i) => ({ label: t.name || `track ${i + 1}`, title: `Track ${i + 1}${t.name ? `: ${t.name}` : ""}`, color, handle: `out:${i + 1}`, layer: "redstone" as Layer })),
            ]
          : signal
            ? [{ label: "out", title: "Output", color, handle: "out:0", layer: "redstone" as Layer }]
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
  const HEADER = BASE_HEADER;
  const pulseGate = $derived(data.node.gate === "pulse");
  const rows = $derived(Math.max(leftRows.length, rightRows.length, 1));
  /**
   * Hovering this card. "Last moved … ago" counts up every second on every node, so it is read only while its tooltip
   * can show: tied to it always, every card would redraw once a second on an idle network.
   */
  let hovered = $state(false);
  const moved = $derived(hovered ? app.ticksSinceMoved[liveKey(id, instanceRun() ?? undefined)] : undefined);
  const idleTitle = $derived(
    [
      data.fiveMinutes > 0 ? `Average over 5 min: ${formatAverage(data.fiveMinutes, data.resource, app.rateUnit)}` : "Nothing moved in the last 5 min",
      !hovered ? "" : moved !== undefined ? `Last moved ${formatAgo(moved)}` : "Never moved",
      data.status ? `Last attempt: ${STATUS_LABEL[data.status] ?? data.status}` : "",
    ]
      .filter(Boolean)
      .join("\n"),
  );

  /** A pin's label, with its live value for redstone ("redstone 15") and ME ("ME 1 ch · 4/8") pins. */
  function pinLabel(side: { resource: import("../../lib/types").Resource; faces?: string[] }, key: string): string {
    if (side.resource === "redstone") return `${shortLabel(side)} ${app.signals[liveKey(key, instanceRun() ?? undefined)] ?? 0}`;
    if (side.resource === "me") return `${shortLabel(side)} ${meUsageLabel(app.me[liveKey(key, instanceRun() ?? undefined)])}`;
    return shortLabel(side);
  }

  // Color says what a pin carries; shape says which way: inputs are hollow rings, outputs filled dots.
  /** A pin on a dimmed or hidden layer is faint. */
  function layerPin(layer: Layer | null | undefined): string {
    const mode = layer ? app.layers[layer] : "show";
    return mode === "dim" || mode === "hide" ? "; opacity: 0.4" : "";
  }
  /** The card's border: thicker for a sheet or placed blueprint. */
  const BORDER = $derived(kind === "instance" ? 3 : kind === "sheet" ? 2 : 1);
  /**
   * How far a thicker border moves the rows down from where pins are placed (for a 1px border): the pins move with
   * them, or their labels sat below them.
   */
  const SHIFT = $derived(data.frame ? 0 : BORDER - 1);
  const inPin = (c: string) => `background: var(--panel); border: 2px solid ${c}`;
  const outPin = (c: string) => `background: ${c}; border: 2px solid ${c}`;
  /** An open frame's handle is the outer half of its pin's dot: just outside the border, the pin inside makes the rest. */
  const outerHalf = (left: boolean) =>
    `; width: ${PIN_KNOB}px; height: ${PIN_KNOB}px; transform: translate(${left ? "-100%" : "100%"}, -50%); border-radius: ${
      left ? `${PIN_KNOB}px 0 0 ${PIN_KNOB}px` : `0 ${PIN_KNOB}px ${PIN_KNOB}px 0`
    }`;

  // New or removed handles must be measured again, or wires attach to stale positions.
  // Re-measure the handles only when the pins or the header actually change. The row arrays are rebuilt with the
  // node's data (every metrics update), so depending on them directly re-measured every node — a forced layout each —
  // once a second.
  const pinLayout = $derived(
    `${leftRows.map((r) => r.handle).join()}|${rightRows.map((r) => r.handle).join()}:${HEADER}:${JSON.stringify(data.frame?.pins ?? null)}`,
  );
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
  class:frame={!!data.frame}
  style="{data.frame
    ? `width: ${width ?? data.frame.width}px; height: ${height ?? data.frame.height}px`
    : `width: max-content; min-width: ${pinned ? 140 : 110}px; max-width: ${pinned ? 220 : 180}px`}; background: var(--panel); border-color: {selected
    ? 'var(--accent)'
    : data.problem
      ? 'var(--bad)'
      : 'var(--line)'}; border-style: {kind === 'instance' ? 'double' : 'solid'}; border-width: {BORDER}px; box-shadow: {selected
    ? '0 0 0 1px var(--accent)'
    : data.problem
      ? '0 0 0 1px var(--bad)'
      : '0 1px 3px rgb(0 0 0 / 0.25)'}; opacity: {disabled ? 0.5 : data.faded ? 0.4 : 1}"
>
  <div data-card-header class="group flex items-center gap-1.5 rounded-t-md px-1.5" style="height: {HEADER}px; border-bottom: 1px solid var(--line); border-left: 3px solid {color}">
    {#if sensor && !data.device && data.reads?.block}
      <!-- A sensor reading a slot: the kind of block the slot takes, faded as the slot's own is. -->
      {@const SensorIcon = KIND_ICON.level}
      <span class="relative shrink-0">
        <span style="opacity: 0.5"><DeviceIcon item={data.reads.block} size={16} /></span>
        <span class="sensor-badge" style="color: {color}"><SensorIcon size={9} /></span>
      </span>
    {:else if data.device && sensor}
      <!-- The device it reads, badged as a sensor so the card isn't taken for the device's own. -->
      {@const SensorIcon = KIND_ICON.level}
      <span class="relative shrink-0">
        <DeviceIcon item={data.device.icon} size={16} />
        <span class="sensor-badge" style="color: {color}"><SensorIcon size={9} /></span>
      </span>
    {:else if data.device}
      <DeviceIcon item={data.device.icon} size={16} />
    {:else if data.slot?.block}
      <!-- A slot shows the kind of block it takes, faded until a block fills it. -->
      <span class="shrink-0" style="opacity: 0.5"><DeviceIcon item={data.slot.block} size={16} /></span>
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
        {#if data.slot}
          {data.slot.filled === undefined ? "slot" : data.slot.filled ? `fills a slot${data.slot.turns ? `, turned ${(data.slot.turns % 4) * 90}°` : ""}` : "empty slot"}
        {:else if signal}
          <span class="tabular-nums" style="color: {(data.signal ?? 0) > 0 ? color : 'var(--muted)'}">{data.signal ?? 0}</span>
          · {sensor && data.device ? `${deviceName(data.device)} · ` : sensor && data.reads ? `${data.reads.name} · ` : ""}{summary(data.node, app.rateUnit)}
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
        {/if}{offline ? ` · ${data.device?.outOfRange ? unreachable(app.snapshot?.antenna).label : data.device?.state}` : ""}{disabled ? " · off" : ""}
      </div>
    </div>
    {#if data.device}<IdentifyBadge device={data.device} />{/if}
    {#if kind === "sheet" && data.holder?.sheet}
      <!-- Opens the sheet in place, as a frame with its nodes inside, or closes it back to a card. -->
      <button
        class="nodrag shrink-0 rounded p-0.5 hover:bg-line"
        title={data.frame ? "Close: show it as a card" : "Open here: show what is inside on this sheet"}
        aria-label={data.frame ? "Close the sheet" : "Open the sheet here"}
        aria-expanded={!!data.frame}
        onclick={(e) => {
          e.stopPropagation();
          toggleFrame(id);
        }}
        ondblclick={(e) => e.stopPropagation()}
      >
        {#if data.frame}<ChevronDown size={14} />{:else}<ChevronRight size={14} />{/if}
      </button>
    {/if}
  </div>
  {#if data.frame}
    <!-- An open frame: its pins sit on its edges, inside it, so no rows here. -->
  {:else if pinned}
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

{#if raised.length}
  {@const critical = raised.some((a) => a.severity === "critical")}
  {@const why = raised.map((a) => `${a.severity === "critical" ? "Critical" : "Warning"}: ${a.message}`).join("\n")}
  <span class="problem-badge" class:warn={!critical} title={why} role="img" aria-label={`Going off: ${why}`}><Siren size={12} /></span>
{:else if data.problem}
  <span class="problem-badge" title={data.problem} role="img" aria-label={`Error: ${data.problem}`}><TriangleAlert size={12} /></span>
{:else if offline}
  <!-- Not an error in the sheet, but it does nothing until its block is back in reach: a warning on the same corner. -->
  {@const why = data.device?.outOfRange ? unreachable(app.snapshot?.antenna).why : data.device?.state === "orphaned" ? "Orphaned — no connector reaches it any more" : "Missing — the block is gone"}
  <span class="problem-badge warn" title={why} role="img" aria-label={`Warning: ${why}`}><TriangleAlert size={12} /></span>
{/if}

{#if gated(kind)}
  <!-- A signal wire into the gate switches the node on (above 0) and off. -->
  <!-- On the left, centered on the header: wires come in from the left like every other input (a pin on top had
       square wires drop into it through whatever sat above), yet it stays apart from the data pins below. -->
  <Handle
    type="target"
    position={Position.Left}
    id="gate"
    class={`gate-pin${data.gateWired ? " wired" : ""}${pulseGate ? " pulse" : ""}`}
    title={pulseGate ? "Gate: one operation each time a signal here turns on" : "Gate: a signal here switches this node on and off"}
    style="top: {HEADER / 2 + SHIFT}px; {inPin('var(--c-signal)')}{layerPin('redstone')}"
  />
{/if}
{#if pinned}
  <!-- An open frame's handles sit on its edges, level with its pins inside; a card's beside their rows. -->
  {#each leftRows as row, i (row.handle)}
    <Handle type="target" position={Position.Left} id={row.handle} title={row.title}
      style="top: {data.frame?.pins[row.handle] ?? HEADER + ROW * i + ROW / 2 + SHIFT}px; {inPin(row.color)}{data.frame ? outerHalf(true) : ''}{layerPin(row.layer)}" />
  {/each}
  {#each rightRows as row, i (row.handle)}
    <Handle type="source" position={Position.Right} id={row.handle} title={row.title}
      style="top: {data.frame?.pins[row.handle] ?? HEADER + ROW * i + ROW / 2 + SHIFT}px; {outPin(row.color)}{data.frame ? outerHalf(false) : ''}{layerPin(row.layer)}" />
  {/each}
{:else}
  {#if flowIn}
    <Handle type="target" position={Position.Left} id="in" style="top: {HEADER + ROW / 2}px; {inPin(color)}{data.faded ? '; opacity: 0.4' : ''}" />
  {/if}
  {#each flowOuts as out, i (out.id)}
    <Handle type="source" position={Position.Right} id={out.id} style="top: {HEADER + ROW * i + ROW / 2}px; {outPin(out.color)}{data.faded ? '; opacity: 0.4' : ''}" />
  {/each}
{/if}

<style>
  /* An open sheet: a frame its nodes sit in, a shade off the canvas so they stand out from it. */
  .frame {
    background: color-mix(in srgb, var(--panel) 55%, transparent) !important;
  }
  /* A Level sensor's mark on the corner of the device icon it shows. */
  .sensor-badge {
    position: absolute;
    right: -4px;
    bottom: -4px;
    display: flex;
    padding: 1px;
    border-radius: 50%;
    background: var(--panel);
  }
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
  .problem-badge.warn {
    background: var(--warn);
  }
  /* The gate is a diamond, so it can't be taken for an input (round pins). */
  :global(.svelte-flow__handle.gate-pin) {
    border-radius: 2px;
    transform: translate(-50%, -50%) rotate(45deg);
  }
  /* A pulse gate is filled: it acts on the signal's rising edge, not its level. */
  :global(.svelte-flow__handle.gate-pin.pulse) {
    background: var(--c-signal) !important;
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
