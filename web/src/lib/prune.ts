// Changing a device node's inputs and outputs: wires on removed ones go, and wires on the ones after them are
// renumbered (the server identifies them by number). Also "Prune pins", for one node or a whole sheet.

import type { DeviceNodeSettings, Edit, GraphNode, Sheet, Wire } from "./types";

export interface Sides {
  in: number[];
  out: number[];
}

/** A device node's inputs and outputs with no wire on them. */
export function unwiredSides(sheet: Sheet, node: GraphNode): Sides {
  const s = node.settings;
  if (s.kind !== "device") return { in: [], out: [] };
  const wires = sheet.wires ?? [];
  return {
    in: (s.inputs ?? []).map((_, i) => i).filter((i) => !wires.some((w) => w.to === node.id && (w.input ?? 0) === i)),
    out: (s.outputs ?? []).map((_, i) => i).filter((i) => !wires.some((w) => w.from === node.id && (w.output ?? 0) === i)),
  };
}

/** The device's settings without the given inputs and outputs. */
export function withoutSides(settings: DeviceNodeSettings, gone: Sides): DeviceNodeSettings {
  return {
    ...settings,
    inputs: (settings.inputs ?? []).filter((_, i) => !gone.in.includes(i)),
    outputs: (settings.outputs ?? []).filter((_, i) => !gone.out.includes(i)),
  };
}

/**
 * The edits (and undo) that store a device node changed from {@code before} to {@code after}, given which inputs and
 * outputs (by old index) were removed: their wires go, and wires on later ones move down to their new numbers.
 */
export function deviceChange(sheet: Sheet, before: GraphNode, after: GraphNode, removed?: Partial<Sides>): { edits: Edit[]; undo: Edit[] } {
  const edits: Edit[] = [];
  const undo: Edit[] = [{ type: "updateNode", sheet: sheet.id, node: before }];
  for (const direction of ["in", "out"] as const) {
    const gone = new Set(removed?.[direction] ?? []);
    if (!gone.size) continue;
    const onSide = (w: Wire) => (direction === "in" ? w.to === before.id : w.from === before.id);
    const index = (w: Wire) => (direction === "in" ? (w.input ?? 0) : (w.output ?? 0));
    const shift = (i: number) => [...gone].filter((g) => g < i).length;
    for (const w of (sheet.wires ?? []).filter((w) => onSide(w) && (gone.has(index(w)) || shift(index(w)) > 0))) {
      edits.push({ type: "disconnect", sheet: sheet.id, wire: w.id });
      undo.push({ type: "connect", sheet: sheet.id, wire: w });
      if (!gone.has(index(w))) {
        const to = index(w) - shift(index(w));
        const moved = direction === "in" ? { ...w, input: to } : { ...w, output: to };
        edits.push({ type: "connect", sheet: sheet.id, wire: moved });
        undo.unshift({ type: "disconnect", sheet: sheet.id, wire: w.id });
      }
    }
  }
  edits.push({ type: "updateNode", sheet: sheet.id, node: after });
  return { edits, undo };
}

/**
 * Prune pins for every device node on a sheet, as one change: the edits, their undo, and how many inputs and
 * outputs go. A wire between two pruned devices is renumbered at both ends at once.
 */
export function pruneSheet(sheet: Sheet): { edits: Edit[]; undo: Edit[]; count: number } {
  const gone = new Map<string, Sides>();
  const updates: Edit[] = [];
  const restores: Edit[] = [];
  let count = 0;
  for (const node of sheet.nodes ?? []) {
    if (node.settings.kind !== "device") continue;
    const sides = unwiredSides(sheet, node);
    if (!sides.in.length && !sides.out.length) continue;
    gone.set(node.id, sides);
    count += sides.in.length + sides.out.length;
    updates.push({ type: "updateNode", sheet: sheet.id, node: { ...node, settings: withoutSides(node.settings, sides) } });
    restores.push({ type: "updateNode", sheet: sheet.id, node });
  }
  const below = (removed: number[] | undefined, i: number) => (removed ?? []).filter((g) => g < i).length;
  const disconnect: Edit[] = [];
  const connect: Edit[] = [];
  const undoDisconnect: Edit[] = [];
  const undoConnect: Edit[] = [];
  // Only unwired sides go, so no wire is lost: wires on later sides just move down to their new numbers.
  for (const w of sheet.wires ?? []) {
    const output = (w.output ?? 0) - below(gone.get(w.from)?.out, w.output ?? 0);
    const input = (w.input ?? 0) < 0 ? (w.input ?? 0) : (w.input ?? 0) - below(gone.get(w.to)?.in, w.input ?? 0);
    if (output === (w.output ?? 0) && input === (w.input ?? 0)) continue;
    disconnect.push({ type: "disconnect", sheet: sheet.id, wire: w.id });
    connect.push({ type: "connect", sheet: sheet.id, wire: { ...w, output, input } });
    undoDisconnect.push({ type: "disconnect", sheet: sheet.id, wire: w.id });
    undoConnect.push({ type: "connect", sheet: sheet.id, wire: w });
  }
  return { edits: [...disconnect, ...connect, ...updates], undo: [...undoDisconnect, ...restores, ...undoConnect], count };
}

/** Device nodes on a sheet with no wire at all (gates included): Prune devices removes them, undoably. */
export function unwiredDevices(sheet: Sheet): GraphNode[] {
  const wires = sheet.wires ?? [];
  return (sheet.nodes ?? []).filter((n) => n.settings.kind === "device" && !wires.some((w) => w.from === n.id || w.to === n.id));
}

export function pruneDevices(sheet: Sheet): { edits: Edit[]; undo: Edit[]; count: number } {
  const gone = unwiredDevices(sheet);
  return {
    edits: gone.map((node) => ({ type: "removeNode", sheet: sheet.id, node: node.id }) as Edit),
    undo: gone.map((node) => ({ type: "addNode", sheet: sheet.id, node }) as Edit),
    count: gone.length,
  };
}
