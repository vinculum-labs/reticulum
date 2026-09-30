// Automatic layout: ELK's layered algorithm, left to right, with each wire on its own pin at the pin's real place.
//
// ELK also plans wire routes, but the canvas draws its own curve from pin to pin. So the layout has to leave room for
// those curves: pins given where they really are (so wires between neighboring columns come out straight), and
// generous spacing around the lanes ELK keeps for wires that skip a column.

import ELK from "elkjs/lib/elk.bundled.js";
import type { Sheet } from "./types";
import { sourceHandle, targetHandle } from "./graph";

const elk = new ELK();

/** A pin's center on its card, from the card's top left (as the canvas measured it). */
export type PinPositions = Map<string, Map<string, { x: number; y: number }>>;

export async function layout(
  sheet: Sheet,
  sizes: Map<string, { width: number; height: number }>,
  pins: PinPositions,
): Promise<{ id: string; x: number; y: number }[]> {
  const byId = new Map((sheet.nodes ?? []).map((n) => [n.id, n]));
  const ports = new Map<string, Set<string>>();
  const port = (node: string, handle: string, side: "WEST" | "EAST" | "NORTH") => {
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
      targets: [port(w.to, targetHandle(byId.get(w.to), w.input ?? 0), (w.input ?? 0) < 0 ? "NORTH" : "WEST")],
    }));
  const graph = {
    id: "root",
    layoutOptions: {
      "elk.algorithm": "layered",
      "elk.direction": "RIGHT",
      "elk.layered.spacing.nodeNodeBetweenLayers": "120",
      "elk.layered.spacing.edgeNodeBetweenLayers": "40",
      "elk.spacing.nodeNode": "40",
      "elk.spacing.edgeNode": "30",
      "elk.spacing.edgeEdge": "15",
      "elk.layered.nodePlacement.strategy": "BRANDES_KOEPF",
    },
    children: (sheet.nodes ?? []).map((n) => {
      const size = sizes.get(n.id) ?? { width: 160, height: 60 };
      return {
        id: n.id,
        width: size.width,
        height: size.height,
        // Pins where the card really has them; in side order when the canvas hasn't measured one yet.
        layoutOptions: { "elk.portConstraints": [...(ports.get(n.id) ?? [])].every((e) => pinAt(pins, n.id, e)) ? "FIXED_POS" : "FIXED_ORDER" },
        ports: [...(ports.get(n.id) ?? [])].map((entry) => {
          const [id, side] = entry.split("|");
          const at = pinAt(pins, n.id, entry);
          return { id, width: 1, height: 1, ...(at ? { x: at.x, y: at.y } : {}), layoutOptions: { "elk.port.side": side } };
        }),
      };
    }),
    edges,
  };
  // First pass: which column each node lands in.
  const first = await elk.layout(structuredClone(graph));
  const columns = [...new Set((first.children ?? []).map((c) => Math.round(c.x ?? 0)))].sort((p, q) => p - q);
  const column = new Map((first.children ?? []).map((c) => [c.id, columns.indexOf(Math.round(c.x ?? 0))]));
  // Second pass: wires that skip a column are kept straight by preference, so the nodes in the columns they cross are
  // moved out of their way (the canvas draws them pin to pin, not along ELK's detours).
  for (const edge of graph.edges) {
    const from = column.get(edge.sources[0].split("::")[0]) ?? 0;
    const to = column.get(edge.targets[0].split("::")[0]) ?? 0;
    (edge as { layoutOptions?: Record<string, string> }).layoutOptions = {
      "elk.layered.priority.straightness": Math.abs(to - from) > 1 ? "10" : "1",
    };
  }
  const result = await elk.layout(graph);
  return (result.children ?? []).map((c) => ({ id: c.id, x: Math.round(c.x ?? 0), y: Math.round(c.y ?? 0) }));
}

function pinAt(pins: PinPositions, node: string, entry: string) {
  const handle = entry.split("|")[0].slice(node.length + 2);
  return pins.get(node)?.get(handle);
}
