// Automatic layout: ELK's layered algorithm, left to right, keeping each pin's wires on its own port.

import ELK from "elkjs/lib/elk.bundled.js";
import type { Sheet } from "./types";
import { sourceHandle, targetHandle } from "./graph";

const elk = new ELK();

export async function layout(sheet: Sheet, sizes: Map<string, { width: number; height: number }>): Promise<{ id: string; x: number; y: number }[]> {
  const byId = new Map((sheet.nodes ?? []).map((n) => [n.id, n]));
  const ports = new Map<string, Set<string>>();
  const port = (node: string, handle: string, side: "WEST" | "EAST") => {
    const id = `${node}::${handle}`;
    if (!ports.has(node)) ports.set(node, new Set());
    ports.get(node)!.add(`${id}|${side}`);
    return id;
  };
  const edges = (sheet.wires ?? [])
    .filter((w) => byId.has(w.from) && byId.has(w.to))
    .map((w) => ({
      id: w.id,
      sources: [port(w.from, sourceHandle(byId.get(w.from), w.output ?? 0), "EAST")],
      targets: [port(w.to, targetHandle(byId.get(w.to), w.input ?? 0), "WEST")],
    }));
  const graph = {
    id: "root",
    layoutOptions: {
      "elk.algorithm": "layered",
      "elk.direction": "RIGHT",
      "elk.layered.spacing.nodeNodeBetweenLayers": "80",
      "elk.spacing.nodeNode": "30",
      "elk.layered.nodePlacement.strategy": "BRANDES_KOEPF",
    },
    children: (sheet.nodes ?? []).map((n) => {
      const size = sizes.get(n.id) ?? { width: 160, height: 60 };
      return {
        id: n.id,
        width: size.width,
        height: size.height,
        layoutOptions: { "elk.portConstraints": "FIXED_ORDER" },
        ports: [...(ports.get(n.id) ?? [])].map((entry) => {
          const [id, side] = entry.split("|");
          return { id, layoutOptions: { "elk.port.side": side } };
        }),
      };
    }),
    edges,
  };
  const result = await elk.layout(graph);
  return (result.children ?? []).map((c) => ({ id: c.id, x: Math.round(c.x ?? 0), y: Math.round(c.y ?? 0) }));
}
