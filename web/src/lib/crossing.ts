// A wire drawn between sheets opened in place on the canvas: it crosses frame edges, and each edge it crosses gets
// a pin (a Sheet output going up, a Sheet input going down), as one change.

import type { Edit, GraphNode, Resource, Sheet, Snapshot, Wire } from "./types";
import { GATE, pinSlot, sheetPins } from "./types";
import { ancestors } from "./tree";
import { pinResource } from "./graph";
import { isSignal } from "./kinds";

export interface End {
  sheet: Sheet;
  node: string;
  /** Output number at the source; input number at the target (GATE for a gate). */
  index: number;
}

/** The slot a new pin on {@code sheet} gets: one past the highest in use. */
function nextSlot(sheet: Sheet, input: boolean): number {
  return sheetPins(sheet, input).reduce((max, pin, i) => Math.max(max, pinSlot(pin, i) + 1), 0);
}

function nodeIn(sheet: Sheet, id: string): GraphNode | undefined {
  return sheet.nodes?.find((n) => n.id === id);
}

/** Room the new pins keep from the sheet's other nodes. */
const GAP = 60;

/** Where a new wire from this output goes in its order: after the others. */
export function nextOrder(sheet: Sheet, node: string, output: number): number {
  return (sheet.wires ?? []).filter((w) => w.from === node && (w.output ?? 0) === output).length;
}

/**
 * The edits (and their undo) that wire {@code from} to {@code to} across the frames between their sheets: up out of
 * the source's sheet through Sheet outputs, down into the target's through Sheet inputs. A pin already carrying the
 * same thing across an edge is used again instead of adding another. Null when the sheets aren't in one tree.
 */
export function crossWire(
  snapshot: Snapshot,
  from: End,
  to: End,
  name: string,
  uuid: () => string,
): { edits: Edit[]; undo: Edit[] } | null {
  const sheets = snapshot.sheets;
  const up = [...ancestors(snapshot, from.sheet), from.sheet];
  const down = [...ancestors(snapshot, to.sheet), to.sheet];
  let common = -1;
  while (common + 1 < up.length && common + 1 < down.length && up[common + 1].id === down[common + 1].id) common++;
  if (common < 0) return null;

  const source = nodeIn(from.sheet, from.node);
  const target = nodeIn(to.sheet, to.node);
  if (!source || !target) return null;
  // What the new pins carry: what leaves the source when that is fixed; signals for signal nodes and gates.
  const carried: Resource | undefined =
    pinResource(source, "out", from.index, sheets) ??
    (isSignal(source.settings.kind) || to.index === GATE ? "redstone" : undefined);

  const edits: Edit[] = [];
  const undo: Edit[] = [];
  const connect = (sheet: Sheet, wire: Omit<Wire, "id" | "order">) => {
    const id = uuid();
    edits.push({ type: "connect", sheet: sheet.id, wire: { ...wire, id, order: nextOrder(sheet, wire.from, wire.output ?? 0) } });
    undo.unshift({ type: "disconnect", sheet: sheet.id, wire: id });
  };
  /**
   * A new pin: an output right of everything, at the height of what feeds it; an input below everything, at the left
   * edge. Either way the sheet's top-left corner stays, so an open frame's contents don't jump.
   */
  const addPin = (sheet: Sheet, input: boolean, y: number): { id: string; slot: number } => {
    const nodes = sheet.nodes ?? [];
    const xs = nodes.map((n) => n.x ?? 0);
    const ys = nodes.map((n) => n.y ?? 0);
    const slot = nextSlot(sheet, input);
    const node: GraphNode = {
      id: uuid(),
      settings: { kind: input ? "sheet_input" : "sheet_output", name, ...(carried ? { resource: carried } : {}), slot },
      x: Math.round(input ? (xs.length ? Math.min(...xs) : 0) : (xs.length ? Math.max(...xs) : 0) + 160 + GAP),
      y: Math.round(input ? (ys.length ? Math.max(...ys) + 80 + GAP : 0) : y),
    };
    edits.push({ type: "addNode", sheet: sheet.id, node });
    // Removing the pin takes the wires on it along.
    undo.unshift({ type: "removeNode", sheet: sheet.id, node: node.id });
    return { id: node.id, slot };
  };

  // Up: out of each sheet below the common one, through a Sheet output; then on from its node one level up.
  let at = { node: from.node, output: from.index, y: source.y ?? 0 };
  for (let i = up.length - 1; i > common; i--) {
    const sheet = up[i];
    const parent = up[i - 1];
    if (!sheet.node) return null;
    const reused = (sheet.wires ?? [])
      .filter((w) => w.from === at.node && (w.output ?? 0) === at.output)
      .map((w) => nodeIn(sheet, w.to))
      .find((n) => n?.settings.kind === "sheet_output");
    let slot: number;
    if (reused) {
      slot = pinSlot(reused, sheetPins(sheet, false).indexOf(reused));
    } else {
      const pin = addPin(sheet, false, at.y);
      slot = pin.slot;
      connect(sheet, { from: at.node, output: at.output, to: pin.id, input: 0 });
    }
    at = { node: sheet.node, output: slot, y: nodeIn(parent, sheet.node)?.y ?? 0 };
  }
  // Down: into each sheet on the way to the target's, through a Sheet input fed from its node.
  for (let i = common + 1; i < down.length; i++) {
    const parent = down[i - 1];
    const sheet = down[i];
    if (!sheet.node) return null;
    const existing = (parent.wires ?? []).find((w) => w.from === at.node && (w.output ?? 0) === at.output && w.to === sheet.node && (w.input ?? 0) !== GATE);
    const reused = existing && sheetPins(sheet, true).find((pin, k) => pinSlot(pin, k) === (existing.input ?? 0));
    let pinId: string;
    if (reused) {
      pinId = reused.id;
    } else {
      const pin = addPin(sheet, true, 0);
      pinId = pin.id;
      connect(parent, { from: at.node, output: at.output, to: sheet.node, input: pin.slot });
    }
    at = { node: pinId, output: 0, y: 0 };
  }
  connect(to.sheet, { from: at.node, output: at.output, to: to.node, input: to.index });
  return { edits, undo };
}
