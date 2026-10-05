// Changing a device node's inputs and outputs: wires on removed ones go, and wires on the ones after them are
// renumbered (the server identifies them by number). Also "Prune pins", for one node or a whole sheet.

import { pinSlot, sheetPins, type DeviceNodeSettings, type Edit, type GraphNode, type Sheet, type Wire } from "./types";

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
  // All disconnects before any reconnect: a wire moved down would otherwise land on one not yet moved (or the removed
  // side's own), the same two nodes wired twice, and the server refuses the whole change.
  const disconnects: Edit[] = [];
  const connects: Edit[] = [];
  const undo: Edit[] = [{ type: "updateNode", sheet: sheet.id, node: before }];
  for (const direction of ["in", "out"] as const) {
    const gone = new Set(removed?.[direction] ?? []);
    if (!gone.size) continue;
    const onSide = (w: Wire) => (direction === "in" ? w.to === before.id : w.from === before.id);
    const index = (w: Wire) => (direction === "in" ? (w.input ?? 0) : (w.output ?? 0));
    const shift = (i: number) => [...gone].filter((g) => g < i).length;
    for (const w of (sheet.wires ?? []).filter((w) => onSide(w) && (gone.has(index(w)) || shift(index(w)) > 0))) {
      disconnects.push({ type: "disconnect", sheet: sheet.id, wire: w.id });
      undo.push({ type: "connect", sheet: sheet.id, wire: w });
      if (!gone.has(index(w))) {
        const to = index(w) - shift(index(w));
        const moved = direction === "in" ? { ...w, input: to } : { ...w, output: to };
        connects.push({ type: "connect", sheet: sheet.id, wire: moved });
        undo.unshift({ type: "disconnect", sheet: sheet.id, wire: w.id });
      }
    }
  }
  return { edits: [...disconnects, ...connects, { type: "updateNode", sheet: sheet.id, node: after }], undo };
}

/**
 * The wires on a sheet pin one level up, as edits that put them back: on its sheet's node, or for a blueprint's pin on
 * every instance node of it. Removing the pin drops them on the server; undo restores them with these.
 */
export function holderWireRestores(sheets: Sheet[], on: Sheet, pin: GraphNode): Edit[] {
  const s = pin.settings;
  if (s.kind !== "sheet_input" && s.kind !== "sheet_output") return [];
  const input = s.kind === "sheet_input";
  const holders: { sheet: Sheet; node: string }[] = on.blueprint
    ? sheets.flatMap((x) => (x.nodes ?? []).filter((h) => h.settings.kind === "instance" && h.settings.sheet === on.id).map((h) => ({ sheet: x, node: h.id })))
    : on.node
      ? sheets.filter((x) => x.nodes?.some((h) => h.id === on.node)).map((x) => ({ sheet: x, node: on.node! }))
      : [];
  const slot = pinSlot(pin, sheetPins(on, input).indexOf(pin));
  const out: Edit[] = [];
  for (const holder of holders) {
    for (const w of holder.sheet.wires ?? []) {
      const attached = input ? w.to === holder.node && (w.input ?? 0) === slot : w.from === holder.node && (w.output ?? 0) === slot;
      if (attached) out.push({ type: "connect", sheet: holder.sheet.id, wire: w });
    }
  }
  return out;
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

/**
 * Device nodes on a sheet with no wire at all (gates included): Prune devices removes them, undoably. A blueprint's
 * slot a Level sensor reads is in use without a wire.
 */
export function unwiredDevices(sheet: Sheet): GraphNode[] {
  const wires = sheet.wires ?? [];
  const read = new Set((sheet.nodes ?? []).flatMap((n) => (n.settings.kind === "level" && n.settings.slot ? [n.settings.slot] : [])));
  return (sheet.nodes ?? []).filter((n) => n.settings.kind === "device" && !read.has(n.id) && !wires.some((w) => w.from === n.id || w.to === n.id));
}

export function pruneDevices(sheet: Sheet): { edits: Edit[]; undo: Edit[]; count: number } {
  const gone = unwiredDevices(sheet);
  return {
    edits: gone.map((node) => ({ type: "removeNode", sheet: sheet.id, node: node.id }) as Edit),
    undo: gone.map((node) => ({ type: "addNode", sheet: sheet.id, node }) as Edit),
    count: gone.length,
  };
}
