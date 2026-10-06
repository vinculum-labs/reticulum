// A wire drawn between sheets opened in place on the canvas: it crosses frame edges, and each edge it crosses gets
// a pin (a Sheet output going up, a Sheet input going down), as one change.

import type { Edit, GraphNode, Resource, Sheet, Snapshot, Wire } from "./types";
import { GATE, pinSlot, sheetPins } from "./types";
import { ancestors } from "./tree";
import { pinResource, resources } from "./graph";
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

/** From a card's top to its first pin row: where a wire meets it, so a new pin goes level with that. */
const LEVEL = 43;

/** Where a new wire from this output goes in its order: after the others (past the highest, as removing one leaves a gap). */
export function nextOrder(sheet: Sheet, node: string, output: number): number {
  const orders = (sheet.wires ?? []).filter((w) => w.from === node && (w.output ?? 0) === output).map((w) => w.order ?? 0);
  return orders.length ? Math.max(...orders) + 1 : 0;
}

/**
 * The edits (and their undo) that wire {@code from} to {@code to} across the frames between their sheets: up out of
 * the source's sheet through Sheet outputs, down into the target's through Sheet inputs. A pin already carrying the
 * same thing across an edge is used again when sharing it splits nothing differently: a signal (it fans out whole),
 * or a resource from an output that has no other wires and isn't a splitter's. Otherwise the wire gets a pin of its
 * own, so it is a branch of its own where its source splits (a splitter's weights, order and nearest-first count it).
 * Null when the sheets aren't in one tree.
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
  // What the wire carries, from either end (an untyped pin at one takes it from the other), as on one sheet: what leaves
  // the source (signals for signal nodes), else what the target takes (signals for gates and signal nodes).
  const sent = isSignal(source.settings.kind) ? "redstone" : (pinResource(source, "out", from.index, sheets) ?? resources(from.sheet, sheets).nodes.get(source.id));
  const taken = to.index === GATE || isSignal(target.settings.kind) ? "redstone" : pinResource(target, "in", to.index, sheets);
  const carried: Resource | undefined = sent ?? taken ?? undefined;

  const edits: Edit[] = [];
  const undo: Edit[] = [];
  // The untyped pins at its ends take it too, as the pins it makes on the frame edges between them do.
  const untype: Edit[] = [];
  const typeEnd = (sheet: Sheet, pin: GraphNode) => {
    const s = pin.settings;
    if (!carried || (s.kind !== "sheet_input" && s.kind !== "sheet_output") || s.resource) return;
    edits.push({ type: "updateNode", sheet: sheet.id, node: { ...pin, settings: { ...s, resource: carried } } });
    untype.push({ type: "updateNode", sheet: sheet.id, node: pin });
  };
  if (source.settings.kind === "sheet_input") typeEnd(from.sheet, source);
  if (target.settings.kind === "sheet_output") typeEnd(to.sheet, target);
  /** Whether a new wire can share the way out of {@code node}'s output that {@code only} already takes. */
  const shareable = (sheet: Sheet, node: string, output: number, only: (w: Wire) => boolean) => {
    if (carried === "redstone") return true;
    if (nodeIn(sheet, node)?.settings.kind === "splitter") return false;
    return (sheet.wires ?? []).filter((w) => w.from === node && (w.output ?? 0) === output).every(only);
  };
  const connect = (sheet: Sheet, wire: Omit<Wire, "id" | "order">) => {
    // A new id: a splitter branch rebuilt here (moveAcross) has its weight moved to it there.
    const id = uuid();
    edits.push({ type: "connect", sheet: sheet.id, wire: { ...wire, id, order: nextOrder(sheet, wire.from, wire.output ?? 0) } });
    undo.unshift({ type: "disconnect", sheet: sheet.id, wire: id });
  };
  /**
   * A new pin on the sheet's edge (inputs left, outputs right), where the wire meets it: level with the node it comes
   * from or goes to in that sheet. A pin's x doesn't matter; its y is its middle.
   */
  const addPin = (sheet: Sheet, input: boolean, y: number): { id: string; slot: number } => {
    const slot = nextSlot(sheet, input);
    const node: GraphNode = {
      id: uuid(),
      settings: { kind: input ? "sheet_input" : "sheet_output", name, ...(carried ? { resource: carried } : {}), slot },
      x: 0,
      y: Math.round(y + LEVEL),
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
    const out = at;
    const found = (sheet.wires ?? [])
      .filter((w) => w.from === out.node && (w.output ?? 0) === out.output)
      .map((w) => nodeIn(sheet, w.to))
      .find((n) => n?.settings.kind === "sheet_output");
    const reused = found && shareable(sheet, out.node, out.output, (w) => w.to === found.id) ? found : undefined;
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
    const here = at;
    const existing = (parent.wires ?? []).find((w) => w.from === here.node && (w.output ?? 0) === here.output && w.to === sheet.node && (w.input ?? 0) !== GATE);
    const shared = existing && shareable(parent, here.node, here.output, (w) => w.id === existing.id);
    const reused = shared && sheetPins(sheet, true).find((pin, k) => pinSlot(pin, k) === (existing.input ?? 0));
    let pinId: string;
    if (reused) {
      pinId = reused.id;
    } else {
      // Level with what it feeds in this sheet: the next sheet's node on the way down, or the target itself.
      const next = i + 1 < down.length ? nodeIn(sheet, down[i + 1].node ?? "") : target;
      const pin = addPin(sheet, true, next?.y ?? 0);
      pinId = pin.id;
      connect(parent, { from: at.node, output: at.output, to: sheet.node, input: pin.slot });
    }
    at = { node: pinId, output: 0, y: 0 };
  }
  // The last wire, unless it is there already (the whole way was reused): nothing new then.
  const there = (to.sheet.wires ?? []).some((w) => w.from === at.node && (w.output ?? 0) === at.output && w.to === to.node && (w.input ?? 0) === to.index);
  if (!there) connect(to.sheet, { from: at.node, output: at.output, to: to.node, input: to.index });
  return { edits, undo: [...undo, ...untype] };
}
