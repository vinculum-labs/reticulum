// Moving cards from one sheet to another on the canvas (dragged across a frame's edge): the cards leave their sheet
// and join the other, and each wire that now crosses an edge is rebuilt through pins, as one change.

import type { Edit, GraphNode, Sheet, Snapshot, Wire } from "./types";
import { crossWire } from "./crossing";

/**
 * The edits (and undo) moving {@code moving} (cards on {@code from}) onto {@code to}, each at its spot there. Wires
 * between the moved cards stay as they are; every other wire on them is rebuilt across the edges between the two
 * sheets, making pins (or reusing them). Null when a wire can't be rebuilt (the sheets aren't in one tree): then
 * nothing moves, rather than a wire being dropped.
 */
export function moveAcross(
  snapshot: Snapshot,
  from: Sheet,
  to: Sheet,
  moving: { node: GraphNode; x: number; y: number }[],
  title: (node: GraphNode) => string,
  uuid: () => string,
): { edits: Edit[]; undo: Edit[] } | null {
  const ids = new Set(moving.map((m) => m.node.id));
  const wires = from.wires ?? [];
  const inside = wires.filter((w) => ids.has(w.from) && ids.has(w.to));
  const outside = wires.filter((w) => ids.has(w.from) !== ids.has(w.to));

  // The sheets as they will be once the cards have moved: what each rebuilt wire sees (and the pins made for the
  // ones before it, so they are reused).
  const moved = moving.map((m) => ({ ...m.node, x: Math.round(m.x), y: Math.round(m.y) }));
  const virtual = new Map<string, Sheet>(snapshot.sheets.map((s) => [s.id, { ...s, nodes: [...(s.nodes ?? [])], wires: [...(s.wires ?? [])] }]));
  const vFrom = virtual.get(from.id)!;
  const vTo = virtual.get(to.id)!;
  vFrom.nodes = vFrom.nodes!.filter((n) => !ids.has(n.id));
  vFrom.wires = vFrom.wires!.filter((w) => !ids.has(w.from) && !ids.has(w.to));
  vTo.nodes!.push(...moved);
  vTo.wires!.push(...inside);
  const view: Snapshot = { ...snapshot, sheets: [...virtual.values()] };

  const edits: Edit[] = [
    ...moving.map((m) => ({ type: "removeNode", sheet: from.id, node: m.node.id }) as Edit),
    ...moved.map((node) => ({ type: "addNode", sheet: to.id, node }) as Edit),
    ...inside.map((wire) => ({ type: "connect", sheet: to.id, wire }) as Edit),
  ];
  const undoCrossings: Edit[][] = [];
  for (const w of outside) {
    const out = ids.has(w.from);
    const source = { sheet: out ? vTo : vFrom, node: w.from, index: w.output ?? 0 };
    const target = { sheet: out ? vFrom : vTo, node: w.to, index: w.input ?? 0 };
    const sourceNode = source.sheet.nodes!.find((n) => n.id === w.from);
    const crossing = crossWire(view, source, target, sourceNode ? title(sourceNode) : "pin", uuid);
    if (!crossing) return null;
    edits.push(...crossing.edits);
    undoCrossings.push(crossing.undo);
    // Later wires see the pins and wires this one made.
    for (const e of crossing.edits) {
      const sheet = virtual.get(e.sheet as string);
      if (!sheet) continue;
      if (e.type === "addNode") sheet.nodes!.push(e.node as GraphNode);
      if (e.type === "connect") sheet.wires!.push(e.wire as Wire);
    }
  }
  // Undone in reverse: the rebuilt wires and their pins, then the cards back where they were, with their wires.
  const undo: Edit[] = [
    ...undoCrossings.reverse().flat(),
    ...moving.map((m) => ({ type: "removeNode", sheet: to.id, node: m.node.id }) as Edit),
    ...moving.map((m) => ({ type: "addNode", sheet: from.id, node: m.node }) as Edit),
    ...[...inside, ...outside].map((wire) => ({ type: "connect", sheet: from.id, wire }) as Edit),
  ];
  return { edits, undo };
}
