// Turns a sheet into Svelte Flow nodes and edges.

import type { Edge, Node } from "@xyflow/svelte";
import type { Device, GraphNode, Resource, Sheet, Wire } from "./types";
import { posKey } from "./types";
import { RESOURCE_VAR } from "./kinds";

export interface NodeData extends Record<string, unknown> {
  node: GraphNode;
  device?: Device;
  resource: Resource | null;
  rate: number;
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
      if (wires.has(w.id)) continue;
      wires.set(w.id, resource);
      const target = byId.get(w.to);
      if (target && target.settings.kind !== "device") {
        if (!nodes.has(w.to)) nodes.set(w.to, resource);
        queue.push([w.to, resource]);
      }
    }
  };
  for (const n of sheet.nodes ?? []) {
    const s = n.settings;
    if (s.kind === "device") (s.outputs ?? []).forEach((o, i) => emit(n.id, o.resource, i));
  }
  while (queue.length) {
    const [id, resource] = queue.shift()!;
    emit(id, resource);
  }
  return { wires, nodes };
}

export function sourceHandle(node: GraphNode | undefined, output: number): string {
  if (node?.settings.kind === "filter") return output === 1 ? "reject" : "match";
  if (node?.settings.kind === "device") return `out:${output}`;
  return "out";
}

export function targetHandle(node: GraphNode | undefined, input: number): string {
  return node?.settings.kind === "device" ? `in:${input}` : "in";
}

/** Reads a handle id back into an output or input number. */
export function handleIndex(handle: string | null | undefined): number {
  if (handle === "reject") return 1;
  const match = handle?.match(/^(?:in|out):(\d+)$/);
  return match ? Number(match[1]) : 0;
}

export function toFlow(
  sheet: Sheet,
  devices: Device[],
  rates: Record<string, number>,
  selected: Set<string>,
): { nodes: Node<NodeData>[]; edges: Edge[] } {
  const byKey = new Map(devices.map((d) => [posKey(d.pos), d]));
  const kinds = resources(sheet);
  const nodes: Node<NodeData>[] = (sheet.nodes ?? []).map((n) => {
    const s = n.settings;
    const device = "device" in s ? byKey.get(posKey(s.device)) : undefined;
    return {
      id: n.id,
      type: "graph",
      position: { x: n.x ?? 0, y: n.y ?? 0 },
      selected: selected.has(n.id),
      data: { node: n, device, resource: kinds.nodes.get(n.id) ?? null, rate: rates[n.id] ?? 0 },
    };
  });
  const byId = new Map((sheet.nodes ?? []).map((n) => [n.id, n]));
  const edges: Edge[] = (sheet.wires ?? []).map((w) => {
    const rate = rates[w.id] ?? 0;
    const resource = kinds.wires.get(w.id) ?? null;
    const color = resource ? RESOURCE_VAR[resource] : "var(--muted)";
    return {
      id: w.id,
      source: w.from,
      target: w.to,
      sourceHandle: sourceHandle(byId.get(w.from), w.output ?? 0),
      targetHandle: targetHandle(byId.get(w.to), w.input ?? 0),
      selected: selected.has(w.id),
      animated: rate > 0,
      label: rate > 0 ? `${rate}/s` : undefined,
      style: `stroke: ${byId.get(w.from)?.settings.kind === "filter" && w.output === 1 ? "var(--bad)" : color}; stroke-width: ${rate > 0 ? 2.5 : 1.5}px`,
    };
  });
  return { nodes, edges };
}
