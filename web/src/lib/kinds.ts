// What each node kind looks like and how it is summarized, plus defaults for new nodes.

import type { Device, GraphNode, NodeKind, NodeSettings, Resource } from "./types";

export const KIND_LABEL: Record<NodeKind, string> = {
  device: "Device",
  filter: "Filter",
  rate_limit: "Rate limit",
  splitter: "Splitter",
  kit: "Kit",
};

export const KIND_HINT: Record<NodeKind, string> = {
  device: "A block that is fed through its inputs and emptied through its outputs",
  filter: "Sends matches one way and the rest another",
  rate_limit: "Caps how much passes per tick (or per second)",
  splitter: "Shares between outputs, in turn or in order",
  kit: "Moves a set of items all together or not at all",
};

export const RESOURCE_LABEL: Record<Resource, string> = { item: "Items", fluid: "Fluid", energy: "Energy" };
export const RESOURCE_UNIT: Record<Resource, string> = { item: "items", fluid: "mB", energy: "FE" };

/** CSS custom property holding the color for a resource's wires. */
export const RESOURCE_VAR: Record<Resource, string> = {
  item: "var(--c-item)",
  fluid: "var(--c-fluid)",
  energy: "var(--c-energy)",
};

export function defaultSettings(kind: NodeKind, device: Device | null, resource: Resource): NodeSettings {
  switch (kind) {
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
  }
}
