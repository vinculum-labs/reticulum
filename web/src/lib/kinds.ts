// What each node kind looks like and how it is summarized, plus defaults for new nodes.

import type { Device, GraphNode, NodeKind, NodeSettings, Resource } from "./types";

export const KIND_LABEL: Record<NodeKind, string> = {
  device: "Device",
  source: "Source",
  sink: "Sink",
  filter: "Filter",
  rate_limit: "Rate limit",
  splitter: "Splitter",
  kit: "Kit",
};

export const KIND_HINT: Record<NodeKind, string> = {
  device: "A block that is fed through its inputs and emptied through its outputs",
  source: "Takes resources out of a device",
  sink: "Puts resources into a device",
  filter: "Sends matches one way and the rest another",
  rate_limit: "Caps how much passes per second",
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
  const port = device ? { resource, device: device.pos, faces: [] } : null;
  switch (kind) {
    case "device":
      return { kind, device: device!.pos, inputs: [{ resource }], outputs: [{ resource, per_operation: 0 }] };
    case "source":
      return { kind, ...port!, per_operation: 0 };
    case "sink":
      return { kind, ...port! };
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

export function formatAmount(value: number, resource: Resource | null): string {
  const unit = resource ? RESOURCE_UNIT[resource] : "";
  if (value >= 1_000_000_000) return `${(value / 1_000_000_000).toFixed(1)}G ${unit}`;
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M ${unit}`;
  if (value >= 10_000) return `${(value / 1000).toFixed(1)}k ${unit}`;
  return `${value} ${unit}`.trim();
}

/** Label for one input or output: resource and faces. */
export function sideLabel(side: { resource: Resource; faces?: string[] }): string {
  return `${RESOURCE_LABEL[side.resource]}${side.faces?.length ? ` · ${side.faces.join(", ")}` : ""}`;
}

/** One short line saying what a node is set to do. */
export function summary(node: GraphNode): string {
  const s = node.settings;
  switch (s.kind) {
    case "device": {
      const ins = s.inputs?.length ?? 0;
      const outs = s.outputs?.length ?? 0;
      return `${ins} input${ins === 1 ? "" : "s"}, ${outs} output${outs === 1 ? "" : "s"}`;
    }
    case "source": {
      const faces = s.faces?.length ? s.faces.join(", ") : "all faces";
      const amount = s.resource === "item" ? `, ${!s.per_operation ? "a stack" : s.per_operation === 1 ? "one" : s.per_operation} per op` : "";
      return `${faces}${amount}${s.keep?.length ? `, keeps ${s.keep.length} rule${s.keep.length > 1 ? "s" : ""}` : ""}`;
    }
    case "sink": {
      const faces = s.faces?.length ? s.faces.join(", ") : "all faces";
      return `${faces}${s.caps?.length ? `, ${s.caps.length} cap${s.caps.length > 1 ? "s" : ""}` : ""}`;
    }
    case "filter":
      return s.expression || "matches everything";
    case "rate_limit":
      return `${s.per_second}/s`;
    case "splitter":
      return s.mode === "priority" ? "in order" : "round robin";
    case "kit":
      return s.lines.map((l) => `${l.count}× ${l.filter || "anything"}`).join(" + ") + (s.max_kits && s.max_kits > 1 ? `, up to ${s.max_kits}` : "");
  }
}
