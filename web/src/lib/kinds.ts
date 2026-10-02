// What each node kind looks like and how it is summarized, plus defaults for new nodes.

import type { Device, DeviceInput, DeviceOutput, Face, GraphNode, NodeKind, NodeSettings, Resource } from "./types";

export const KIND_LABEL: Record<NodeKind, string> = {
  device: "Device",
  filter: "Filter",
  rate_limit: "Rate limit",
  splitter: "Splitter",
  kit: "Kit",
  sheet: "Sheet",
  copy: "Blueprint",
  sheet_input: "Sheet input",
  sheet_output: "Sheet output",
  level: "Level sensor",
  compare: "Compare",
  logic: "Logic",
  timer: "Timer",
  pulse: "Pulse",
  latch: "Latch",
  counter: "Counter",
  delay: "Delay",
};

export const KIND_HINT: Record<NodeKind, string> = {
  device: "A block that is fed through its inputs and emptied through its outputs",
  filter: "Sends matches one way and the rest another",
  rate_limit: "Caps how much passes per tick (or per second)",
  splitter: "Shares between outputs: in turn, in order, by weight, or nearest first",
  kit: "Moves a set of items all together or not at all",
  sheet: "A sheet inside this one: double-click to open it. Its switch and gate switch everything in it",
  copy: "A copy of a blueprint, used like one node: double-click it to fill its slots with blocks",
  sheet_input: "Where resources or signals come into this sheet: a pin on the left of its node",
  sheet_output: "Where resources or signals leave this sheet: a pin on the right of its node",
  level: "How much of a resource a device holds",
  compare: "15 when A compares true against B (or a number), else 0",
  logic: "AND, OR, NOT / NOR, XOR, NAND or XNOR of every wire into it",
  timer: "A one-tick pulse every so many ticks",
  pulse: "A short pulse each time its input turns on",
  latch: "Set turns it on, reset turns it off; it remembers in between",
  counter: "Counts each time its input turns on; reset sets it back to 0",
  delay: "Its input, some ticks later",
};

/** Redstone (signal) nodes, in the order of the toolbar's Redstone menu. */
export const SIGNAL_KINDS: NodeKind[] = [
  "level",
  "compare",
  "logic",
  "timer",
  "pulse",
  "latch",
  "counter",
  "delay",
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
    case "logic":
    case "pulse":
    case "delay":
      return ["in"];
    default:
      return [];
  }
}

/** Whether a signal node gives an output to wire on. */
export function signalOutput(kind: NodeKind): boolean {
  return isSignal(kind);
}

/** Kinds a signal wire can switch on and off through their gate (the diamond pin). */
export function gated(kind: NodeKind): boolean {
  return !isSignal(kind) && kind !== "sheet_input" && kind !== "sheet_output";
}


export const RESOURCE_LABEL: Record<Resource, string> = {
  item: "Items",
  fluid: "Fluid",
  energy: "Energy",
  chemical: "Chemical",
  redstone: "Redstone",
  me: "ME (AE2)",
};
export const RESOURCE_UNIT: Record<Resource, string> = { item: "items", fluid: "mB", energy: "FE", chemical: "mB", redstone: "", me: "channels" };

/** Resources that flow; redstone and ME pins carry signals and links instead. */
export function moves(resource: Resource): boolean {
  return resource !== "redstone" && resource !== "me";
}

/**
 * The resources a device offers on some face, most specific first: items, fluids and chemicals, then ME (a block on an
 * ME network is there for that, like the ME controller, which also takes energy), then energy, then redstone (which
 * every device touching a connector offers), so a new device node starts with what the device is really for.
 */
export function offeredResources(resources: Partial<Record<Resource, string[]>> | undefined): Resource[] {
  return (["item", "fluid", "chemical", "me", "energy", "redstone"] as Resource[]).filter((r) => resources?.[r]?.length);
}

/** CSS custom property holding the color for a resource's wires. */
export const RESOURCE_VAR: Record<Resource, string> = {
  item: "var(--c-item)",
  fluid: "var(--c-fluid)",
  energy: "var(--c-energy)",
  chemical: "var(--c-chemical)",
  redstone: "var(--c-signal)",
  me: "var(--c-me)",
};

/**
 * A new device node's pins, from every face of the device that touches a connector: one input and one output per
 * resource those faces offer (tied to those faces), energy only in the directions they allow, and redstone pins only
 * for a device that offers nothing else (every face touching a connector offers redstone). Null when nothing is found.
 */
function probedPins(device: Device): { inputs: DeviceInput[]; outputs: DeviceOutput[] } | null {
  const touching = new Set<string>((device.resources?.redstone ?? []).filter((f) => f !== "unsided"));
  if (!touching.size) return null;
  const on = (faces: string[] | undefined) => (faces ?? []).filter((f) => touching.has(f)) as Face[];
  const inputs: DeviceInput[] = [];
  const outputs: DeviceOutput[] = [];
  for (const resource of ["item", "fluid", "chemical", "me"] as Resource[]) {
    const faces = on(device.resources?.[resource]);
    if (!faces.length) continue;
    inputs.push({ resource, faces });
    outputs.push({ resource, faces, per_operation: 0 });
  }
  const energyIn = on(device.energyIn);
  const energyOut = on(device.energyOut);
  if (energyIn.length) inputs.push({ resource: "energy", faces: energyIn });
  if (energyOut.length) outputs.push({ resource: "energy", faces: energyOut, per_operation: 0 });
  // Redstone only for blocks that are about redstone (levers, lamps, doors, pistons): the test for "takes" counts
  // any machine and "gives" any comparator reading, so a barrel would get both. Those can be added by hand.
  if (!inputs.length && !outputs.length) {
    if (device.takesRedstone) inputs.push({ resource: "redstone", faces: [...touching] as Face[] });
    if (device.givesRedstone) outputs.push({ resource: "redstone", faces: [...touching] as Face[] });
  }
  return inputs.length || outputs.length ? { inputs, outputs } : null;
}

export function defaultSettings(
  kind: NodeKind,
  device: Device | null,
  resource: Resource,
  blueprint?: string,
): NodeSettings {
  switch (kind) {
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
    case "copy":
    case "sheet":
      return { kind, sheet: blueprint ?? "" };
    case "sheet_input":
    case "sheet_output":
      return { kind, name: "" };
    case "device": {
      const probed = device ? probedPins(device) : null;
      if (probed) return { kind, device: device!.pos, ...probed };
      // A redstone device gets the pins it is for: a lever only an output, a lamp only an input.
      const redstone = resource === "redstone" && (device?.givesRedstone || device?.takesRedstone);
      return {
        kind,
        device: device!.pos,
        inputs: redstone && !device?.takesRedstone ? [] : [{ resource }],
        outputs: redstone && !device?.givesRedstone ? [] : [{ resource, per_operation: 0 }],
      };
    }
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

/** "1 ch · 4/8": channels through the port, then the fullest cable on the way to the ME controller. */
export function meUsageLabel(usage: { connected: boolean; through: number; used: number; max: number } | undefined): string {
  if (!usage) return "not linked";
  if (!usage.connected) return "not connected";
  return `${usage.through} ch · ${usage.used}/${usage.max}`;
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
  no_power: "no power",
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
  const name = side.resource === "item" ? "items" : side.resource === "me" ? "ME" : side.resource;
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
      return s.expression || "anything";
    case "rate_limit":
      return unit === "t" ? `${Number((s.per_second / 20).toFixed(2))}/t` : `${s.per_second}/s`;
    case "splitter":
      return s.mode === "priority"
        ? "in order"
        : s.mode === "weighted"
          ? `weighted ${(s.weights?.length ? s.weights : [1]).join(":")}`
          : s.mode === "nearest"
            ? "nearest first"
            : "round robin";
    case "kit":
      return s.lines.map((l) => `${l.count}× ${l.filter || "anything"}`).join(" + ") + (s.max_kits && s.max_kits > 1 ? `, up to ${s.max_kits}` : "");
    case "copy":
      return "blueprint";
    case "sheet":
      return "sheet";
    case "sheet_input":
    case "sheet_output":
      return `${s.name || "unnamed pin"}${s.resource ? ` · ${RESOURCE_LABEL[s.resource].toLowerCase()}` : ""}`;
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
  }
}

export const OP_SYMBOL: Record<string, string> = { lt: "<", le: "≤", gt: ">", ge: "≥", eq: "=", ne: "≠" };
