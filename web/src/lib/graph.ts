// Turns a sheet into Svelte Flow nodes and edges.

import type { Edge, Node } from "@xyflow/svelte";
import type { Device, GraphNode, Resource, Sheet } from "./types";
import { posKey } from "./types";
import { RESOURCE_VAR } from "./kinds";

export interface NodeData extends Record<string, unknown> {
  node: GraphNode;
  device?: Device;
  resource: Resource | null;
  rate: number;
}

/** Which resource flows through each node: follow wires out from every source. */
export function resources(sheet: Sheet): Map<string, Resource> {
  const out = new Map<string, Resource>();
  const next = new Map<string, string[]>();
  for (const w of sheet.wires ?? []) next.set(w.from, [...(next.get(w.from) ?? []), w.to]);
  for (const n of sheet.nodes ?? []) {
    const s = n.settings;
    if (s.kind !== "source" && s.kind !== "sink") continue;
    out.set(n.id, s.resource);
    if (s.kind !== "source") continue;
    const queue = [n.id];
    while (queue.length) {
      const id = queue.shift()!;
      for (const to of next.get(id) ?? []) {
        if (!out.has(to)) {
          out.set(to, s.resource);
          queue.push(to);
        }
      }
    }
  }
  return out;
}

export function handleFor(node: GraphNode | undefined, output: number): string {
  if (node?.settings.kind === "filter") return output === 1 ? "reject" : "match";
  return "out";
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
      data: { node: n, device, resource: kinds.get(n.id) ?? null, rate: rates[n.id] ?? 0 },
    };
  });
  const byId = new Map((sheet.nodes ?? []).map((n) => [n.id, n]));
  const edges: Edge[] = (sheet.wires ?? []).map((w) => {
    const rate = rates[w.id] ?? 0;
    const resource = kinds.get(w.from) ?? null;
    const color = resource ? RESOURCE_VAR[resource] : "var(--muted)";
    return {
      id: w.id,
      source: w.from,
      target: w.to,
      sourceHandle: handleFor(byId.get(w.from), w.output ?? 0),
      targetHandle: "in",
      selected: selected.has(w.id),
      animated: rate > 0,
      label: rate > 0 ? `${rate}/s` : undefined,
      style: `stroke: ${w.output === 1 ? "var(--bad)" : color}; stroke-width: ${rate > 0 ? 2.5 : 1.5}px`,
    };
  });
  return { nodes, edges };
}
