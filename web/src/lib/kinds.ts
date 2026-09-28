// What each node kind looks like and how it is summarized, plus defaults for new nodes.

import type { Device, GraphNode, NodeKind, NodeSettings, Redstone, Resource } from "./types";

export const KIND_LABEL: Record<NodeKind, string> = {
  device: "Device",
  filter: "Filter",
  rate_limit: "Rate limit",
  splitter: "Splitter",
  kit: "Kit",
  subsheet: "Sub-sheet",
  sheet_input: "Sheet input",
  sheet_output: "Sheet output",
  redstone_in: "Redstone in",
  redstone_out: "Redstone out",
  level: "Level sensor",
  compare: "Compare",
  logic: "Logic",
  timer: "Timer",
  pulse: "Pulse",
  latch: "Latch",
  counter: "Counter",
  delay: "Delay",
  publish: "Publish",
  signal: "Network signal",
};

export const KIND_HINT: Record<NodeKind, string> = {
  device: "A block that is fed through its inputs and emptied through its outputs",
  filter: "Sends matches one way and the rest another",
  rate_limit: "Caps how much passes per tick (or per second)",
  splitter: "Shares between outputs, in turn or in order",
  kit: "Moves a set of items all together or not at all",
  subsheet: "A copy of a sheet marked as a sub-sheet, used like one node",
  sheet_input: "Inside a sub-sheet: where resources come in (a pin on the left of the sub-sheet node)",
  sheet_output: "Inside a sub-sheet: where resources go out (a pin on the right of the sub-sheet node)",
  redstone_in: "The redstone a device gives out (or its comparator value)",
  redstone_out: "Gives its input as redstone to a device, from the connectors next to it",
  level: "How much of a resource a device holds",
  compare: "15 when A compares true against B (or a number), else 0",
  logic: "AND, OR, NOT or XOR of every wire into it",
  timer: "A one-tick pulse every so many ticks",
  pulse: "A short pulse each time its input turns on",
  latch: "Set turns it on, reset turns it off; it remembers in between",
  counter: "Counts each time its input turns on; reset sets it back to 0",
  delay: "Its input, some ticks later",
  publish: "Publishes its input under a name that any sheet can read",
  signal: "Reads a published network signal",
};

/** Signal nodes, in palette order. */
export const SIGNAL_KINDS: NodeKind[] = [
  "redstone_in",
  "redstone_out",
  "level",
  "compare",
  "logic",
  "timer",
  "pulse",
  "latch",
  "counter",
  "delay",
  "publish",
  "signal",
];

export function isSignal(kind: NodeKind | undefined): boolean {
  return !!kind && SIGNAL_KINDS.includes(kind);
}

/** Names of a signal node's inputs, top to bottom. */
export function signalInputs(kind: NodeKind): string[] {
  switch (kind) {
    case "compare":
      return ["A", "B"];
    case "latch":
      return ["set", "reset"];
    case "counter":
      return ["count", "reset"];
    case "timer":
      return ["run"];
    case "redstone_out":
    case "logic":
    case "pulse":
    case "delay":
    case "publish":
      return ["in"];
    default:
      return [];
  }
}

/** Whether a signal node gives an output to wire on. */
export function signalOutput(kind: NodeKind): boolean {
  return kind !== "redstone_out" && kind !== "publish";
}

/** Kinds a signal wire can switch on and off through their gate (the pin on top). */
export function gated(kind: NodeKind): boolean {
  return !isSignal(kind) && kind !== "sheet_input" && kind !== "sheet_output";
}

export const REDSTONE_MODE_LABEL: Record<string, string> = {
  ignored: "Ignored",
  high: "High (1–15)",
  low: "Low (0)",
  pulse: "Pulse (rising edge)",
};

/** "redstone high at the controller" and the like, for badges and hovers. */
export function redstoneSummary(r: Redstone | undefined): string {
  if (!r || !r.mode || r.mode === "ignored") return "";
  const where =
    r.source === "connector" ? "a connector" : r.source === "face" ? "a device face" : r.source === "signal" ? `“${r.signal ?? ""}”` : "default";
  return `redstone ${r.mode} · ${where}`;
}

export const RESOURCE_LABEL: Record<Resource, string> = { item: "Items", fluid: "Fluid", energy: "Energy", chemical: "Chemical" };
export const RESOURCE_UNIT: Record<Resource, string> = { item: "items", fluid: "mB", energy: "FE", chemical: "mB" };

/** The resources a device offers on some face, in palette order. */
export function offeredResources(resources: Partial<Record<Resource, string[]>> | undefined): Resource[] {
  return (["item", "fluid", "energy", "chemical"] as Resource[]).filter((r) => resources?.[r]?.length);
}

/** CSS custom property holding the color for a resource's wires. */
export const RESOURCE_VAR: Record<Resource, string> = {
  item: "var(--c-item)",
  fluid: "var(--c-fluid)",
  energy: "var(--c-energy)",
  chemical: "var(--c-chemical)",
};

export function defaultSettings(kind: NodeKind, device: Device | null, resource: Resource, subsheet?: string): NodeSettings {
  switch (kind) {
    case "redstone_in":
      return { kind, device: device!.pos };
    case "redstone_out":
      return { kind, device: device!.pos };
    case "level":
      return { kind, device: device!.pos, resource, filter: "" };
    case "compare":
      return { kind, op: "ge", constant: 1 };
    case "logic":
      return { kind, op: "and" };
    case "timer":
      return { kind, interval: 20 };
    case "pulse":
      return { kind, length: 1 };
    case "latch":
    case "counter":
      return { kind };
    case "delay":
      return { kind, ticks: 1 };
    case "publish":
    case "signal":
      return { kind, name: "" };
    case "subsheet":
      return { kind, sheet: subsheet ?? "" };
    case "sheet_input":
    case "sheet_output":
      return { kind, name: "" };
    case "device":
      return { kind, device: device!.pos, inputs: [{ resource }], outputs: [{ resource, per_operation: 0 }] };
    case "filter":
      return { kind, expression: "" };
    case "rate_limit":
      return { kind, per_second: resource === "item" ? 64 : 1000 };
    case "splitter":
      return { kind, mode: "round_robin" };
    case "kit":
      return { kind, lines: [{ filter: "", count: 1 }], max_kits: 1 };
  }
}

/** Why a node moved nothing, in words. */
export const STATUS_LABEL: Record<string, string> = {
  device_offline: "device offline",
  source_empty: "nothing to take",
  keep_rule: "keep rule reached",
  filtered_out: "filtered out",
  target_offline: "target offline",
  rate_limited: "rate limited",
  cap_reached: "cap reached",
  target_full: "target full",
  waiting_for_parts: "waiting for parts",
  no_room_for_kit: "no room for a kit",
  overflow_full: "overflow full",
  switched_off: "switched off by redstone",
};

/**
 * An average over the last 5 minutes. The unit steps up for slow flows so the number stays readable:
 * per tick (or second), then per minute, then per hour.
 */
export function formatAverage(fiveMinuteTotal: number, resource: Resource | null, unit: "t" | "s"): string {
  const perSecond = fiveMinuteTotal / 300;
  const preferred = unit === "t" ? perSecond / 20 : perSecond;
  if (preferred >= 0.1) return `${formatAmount(round(preferred), resource)}/${unit}`;
  if (perSecond * 60 >= 0.1) return `${formatAmount(round(perSecond * 60), resource)}/min`;
  return `${formatAmount(round(perSecond * 3600), resource)}/h`;
}

function round(v: number): number {
  return v >= 10 ? Math.round(v * 10) / 10 : Math.round(v * 100) / 100;
}

/** "12s ago", "4m ago", "2h ago". */
export function formatAgo(ticks: number): string {
  const seconds = Math.floor(ticks / 20);
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  return `${Math.floor(seconds / 3600)}h ago`;
}

/** A per-second amount (as the server counts) in the chosen unit, e.g. "3.2/t" or "64/s". */
export function formatRate(perSecond: number, resource: Resource | null, unit: "t" | "s"): string {
  const value = unit === "t" ? perSecond / 20 : perSecond;
  return `${formatAmount(value, resource)}/${unit}`;
}

export function formatAmount(value: number, resource: Resource | null): string {
  const unit = resource ? RESOURCE_UNIT[resource] : "";
  if (value >= 1_000_000_000) return `${(value / 1_000_000_000).toFixed(1)}G ${unit}`;
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M ${unit}`;
  if (value >= 10_000) return `${(value / 1000).toFixed(1)}k ${unit}`;
  const shown = Number.isInteger(value) ? String(value) : value >= 10 ? value.toFixed(1) : String(Number(value.toFixed(2)));
  return `${shown} ${unit}`.trim();
}

/** Label for one input or output: resource and faces. */
export function sideLabel(side: { resource: Resource; faces?: string[] }): string {
  return `${RESOURCE_LABEL[side.resource]}${side.faces?.length ? ` · ${side.faces.join(", ")}` : ""}`;
}

/** Compact label shown next to a handle: the resource, and the faces when they are chosen. */
export function shortLabel(side: { resource: Resource; faces?: string[] }): string {
  const name = side.resource === "item" ? "items" : side.resource;
  return side.faces?.length ? `${name} (${side.faces.map((f) => f[0].toUpperCase()).join("")})` : name;
}

/** One short line saying what a node is set to do. */
export function summary(node: GraphNode, unit: "t" | "s" = "t"): string {
  const s = node.settings;
  switch (s.kind) {
    case "device": {
      const ins = s.inputs?.length ?? 0;
      const outs = s.outputs?.length ?? 0;
      return `${ins} input${ins === 1 ? "" : "s"}, ${outs} output${outs === 1 ? "" : "s"}`;
    }
    case "filter":
      return s.expression || "matches everything";
    case "rate_limit":
      return unit === "t" ? `${Number((s.per_second / 20).toFixed(2))}/t` : `${s.per_second}/s`;
    case "splitter":
      return s.mode === "priority" ? "in order" : "round robin";
    case "kit":
      return s.lines.map((l) => `${l.count}× ${l.filter || "anything"}`).join(" + ") + (s.max_kits && s.max_kits > 1 ? `, up to ${s.max_kits}` : "");
    case "subsheet":
      return "sub-sheet";
    case "sheet_input":
    case "sheet_output":
      return s.name || "unnamed pin";
    case "redstone_in":
      return s.comparator ? "comparator value" : `signal${s.face ? ` · ${s.face}` : ""}`;
    case "redstone_out":
      return "to device";
    case "level":
      return `${RESOURCE_UNIT[s.resource]}${s.filter ? ` · ${s.filter}` : ""}`;
    case "compare":
      return `A ${OP_SYMBOL[s.op ?? "ge"]} ${s.constant ?? 1} (or B)`;
    case "logic":
      return (s.op ?? "and").toUpperCase();
    case "timer":
      return `every ${s.interval ?? 20} ticks`;
    case "pulse":
      return `${s.length ?? 1} tick${(s.length ?? 1) === 1 ? "" : "s"}`;
    case "latch":
      return "set / reset";
    case "counter":
      return "count / reset";
    case "delay":
      return `${s.ticks ?? 1} tick${(s.ticks ?? 1) === 1 ? "" : "s"} late`;
    case "publish":
    case "signal":
      return s.name ? `“${s.name}”` : "no name";
  }
}

export const OP_SYMBOL: Record<string, string> = { lt: "<", le: "≤", gt: ">", ge: "≥", eq: "=", ne: "≠" };
