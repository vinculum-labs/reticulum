// Turns a sheet into Svelte Flow nodes and edges.

import type { Edge, Node } from "@xyflow/svelte";
import type { Device, GlobalPos, GraphNode, MeUsage, Resource, Sheet, Wire } from "./types";
export type { MeUsage };
import { GATE, pinName, posKey, sheetPins } from "./types";
import { RESOURCE_VAR, formatRate, isSignal } from "./kinds";

export interface NodeData extends Record<string, unknown> {
  node: GraphNode;
  device?: Device;
  resource: Resource | null;
  rate: number;
  /** Moved over the last 5 minutes. */
  fiveMinutes: number;
  /** Ticks since it last moved, if it ever did. */
  ticksSinceMoved?: number;
  /** Why it moved nothing on its last attempt. */
  status?: string;
  /** For a sub-sheet node: the sheet it copies and its pin names. */
  subsheet?: { sheet?: Sheet; inputs: string[]; outputs: string[] };
  /** For a signal node: its output as last reported. */
  signal?: number;

}

export interface Activity {
  rates: Record<string, number>;
  fiveMinutes: Record<string, number>;
  ticksSinceMoved: Record<string, number>;
  status: Record<string, string>;
  signals: Record<string, number>;
  me: Record<string, MeUsage>;
}

/** The devices whose outputs reach a node through the sheet's wires (not gate wires), nearest first. */
export function upstreamDevices(sheet: Sheet, nodeId: string): GlobalPos[] {
  const byId = new Map((sheet.nodes ?? []).map((n) => [n.id, n]));
  const found: GlobalPos[] = [];
  const seen = new Set<string>([nodeId]);
  let frontier = [nodeId];
  while (frontier.length) {
    const next: string[] = [];
    for (const id of frontier) {
      for (const w of sheet.wires ?? []) {
        if (w.to !== id || (w.input ?? 0) === GATE || seen.has(w.from)) continue;
        seen.add(w.from);
        const from = byId.get(w.from);
        if (!from) continue;
        if (from.settings.kind === "device") found.push(from.settings.device);
        else next.push(from.id);
      }
    }
    frontier = next;
  }
  return found;
}

/**
 * Which resource each wire carries: what its source or device output emits, passed on through filters, rate
 * limits and the like. Also returns a resource per node for coloring.
 */
export function resources(sheet: Sheet): { wires: Map<string, Resource>; nodes: Map<string, Resource> } {
  const wires = new Map<string, Resource>();
  const nodes = new Map<string, Resource>();
  const byId = new Map((sheet.nodes ?? []).map((n) => [n.id, n]));
  const outgoing = new Map<string, Wire[]>();
  for (const w of sheet.wires ?? []) outgoing.set(w.from, [...(outgoing.get(w.from) ?? []), w]);
  const queue: [string, Resource][] = [];
  for (const n of sheet.nodes ?? []) {
    const s = n.settings;
    if (s.kind === "device") {
      const first = s.outputs?.[0]?.resource ?? s.inputs?.[0]?.resource;
      if (first) nodes.set(n.id, first);
    }
  }
  const emit = (from: string, resource: Resource, only?: number) => {
    for (const w of outgoing.get(from) ?? []) {
      if (only !== undefined && (w.output ?? 0) !== only) continue;
      if (wires.has(w.id) || (w.input ?? 0) === GATE) continue;
      wires.set(w.id, resource);
      const target = byId.get(w.to);
      if (target && target.settings.kind !== "device" && !isSignal(target.settings.kind)) {
        if (!nodes.has(w.to)) nodes.set(w.to, resource);
        queue.push([w.to, resource]);
      }
    }
  };
  for (const n of sheet.nodes ?? []) {
    const s = n.settings;
    if (s.kind === "device") (s.outputs ?? []).forEach((o, i) => o.resource !== "redstone" && o.resource !== "me" && emit(n.id, o.resource, i));
  }
  while (queue.length) {
    const [id, resource] = queue.shift()!;
    emit(id, resource);
  }
  return { wires, nodes };
}

/** What a device node's input or output pin carries (null for other nodes). */
export function pinResource(node: GraphNode | undefined, direction: "in" | "out", index: number): Resource | null {
  const s = node?.settings;
  if (s?.kind !== "device") return null;
  return (direction === "in" ? s.inputs?.[index]?.resource : s.outputs?.[index]?.resource) ?? null;
}

/** Kinds whose inputs and outputs are numbered pins ("in:0", "out:1"). */
function numbered(node: GraphNode | undefined): boolean {
  return node?.settings.kind === "device" || node?.settings.kind === "subsheet" || isSignal(node?.settings.kind);
}

export function sourceHandle(node: GraphNode | undefined, output: number): string {
  if (node?.settings.kind === "filter") return output === 1 ? "reject" : "match";
  if (numbered(node)) return `out:${output}`;
  return "out";
}

export function targetHandle(node: GraphNode | undefined, input: number): string {
  if (input === GATE) return "gate";
  return numbered(node) ? `in:${input}` : "in";
}

/** Reads a handle id back into an output or input number. */
export function handleIndex(handle: string | null | undefined): number {
  if (handle === "reject") return 1;
  if (handle === "gate") return GATE;
  const match = handle?.match(/^(?:in|out):(\d+)$/);
  return match ? Number(match[1]) : 0;
}

export function toFlow(
  sheet: Sheet,
  sheets: Sheet[],
  devices: Device[],
  activity: Activity,
  selected: Set<string>,
  unit: "t" | "s",
): { nodes: Node<NodeData>[]; edges: Edge[] } {
  const rates = activity.rates;
  const byKey = new Map(devices.map((d) => [posKey(d.pos), d]));
  const kinds = resources(sheet);
  const nodes: Node<NodeData>[] = (sheet.nodes ?? []).map((n) => {
    const s = n.settings;
    const device = "device" in s ? byKey.get(posKey(s.device)) : undefined;
    let subsheet: NodeData["subsheet"];
    if (s.kind === "subsheet") {
      const used = sheets.find((x) => x.id === s.sheet);
      subsheet = {
        sheet: used,
        inputs: sheetPins(used, true).map(pinName),
        outputs: sheetPins(used, false).map(pinName),
      };
    }
    return {
      id: n.id,
      type: "graph",
      position: { x: n.x ?? 0, y: n.y ?? 0 },
      selected: selected.has(n.id),
      data: {
        node: n,
        device,
        resource: kinds.nodes.get(n.id) ?? null,
        rate: rates[n.id] ?? 0,
        fiveMinutes: activity.fiveMinutes[n.id] ?? 0,
        ticksSinceMoved: activity.ticksSinceMoved[n.id],
        status: activity.status[n.id],
        subsheet,
        signal: isSignal(s.kind) ? (activity.signals[n.id] ?? 0) : undefined,
      },
    };
  });
  const byId = new Map((sheet.nodes ?? []).map((n) => [n.id, n]));
  const edges: Edge[] = (sheet.wires ?? []).map((w) => {
    const rate = rates[w.id] ?? 0;
    const resource = kinds.wires.get(w.id) ?? null;
    const fromNode = byId.get(w.from);
    const signal = isSignal(fromNode?.settings.kind) || pinResource(fromNode, "out", w.output ?? 0) === "redstone";
    const on = signal && (activity.signals[isSignal(fromNode?.settings.kind) ? w.from : `${w.from}/out/${w.output ?? 0}`] ?? 0) > 0;
    const pin = pinResource(fromNode, "out", w.output ?? 0);
    const color = signal || pin === "redstone" ? "var(--c-signal)" : pin === "me" ? "var(--c-me)" : resource ? RESOURCE_VAR[resource] : "var(--muted)";
    return {
      id: w.id,
      source: w.from,
      target: w.to,
      sourceHandle: sourceHandle(byId.get(w.from), w.output ?? 0),
      targetHandle: targetHandle(byId.get(w.to), w.input ?? 0),
      selected: selected.has(w.id),
      animated: rate > 0,
      label: rate > 0 ? formatRate(rate, resource, unit) : undefined,
      style: signal
        ? `stroke: ${color}; stroke-width: ${on ? 2.5 : 1.5}px; stroke-dasharray: ${on ? "none" : "4 3"}; opacity: ${on ? 1 : 0.7}`
        : `stroke: ${fromNode?.settings.kind === "filter" && w.output === 1 ? "var(--bad)" : color}; stroke-width: ${rate > 0 ? 2.5 : 1.5}px`,
    };
  });
  return { nodes, edges };
}
