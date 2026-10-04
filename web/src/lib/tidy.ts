import { isPin, openFrameSize, PIN_DOT, PIN_H, type FlowView } from "./graph";
import { layout, type PinPositions } from "./layout";
import type { Edit, Sheet } from "./types";

/**
 * Tidy up: the moves that lay a sheet out left to right with ELK, and their undo. The sheets open in frames on it go
 * first, innermost first, then each sheet around them with its frames at their new sizes.
 *
 * @param sizes every card's size on the canvas, as measured (frames' are replaced as their insides are laid out)
 * @param pins each card's pins' centers on it, as measured
 * @param open the sheets shown open in frames
 */
export async function tidyMoves(
  sheet: Sheet,
  sheets: Sheet[],
  view: FlowView,
  sizes: Map<string, { width: number; height: number }>,
  pins: PinPositions,
  open: Set<string>,
): Promise<{ edits: Edit[]; undo: Edit[] }> {
  const relaid = new Map<string, Sheet>();
  const sheetsNow = () => sheets.map((x) => relaid.get(x.id) ?? x);
  const edits: Edit[] = [];
  const undo: Edit[] = [];
  /** Lays one sheet out, its open frames sized from their (already laid out) insides; queues the move. */
  const tidySheet = async (target: Sheet, frames: [string, string][]) => {
    for (const [frameId, inner] of frames) {
      const held = relaid.get(inner);
      if (held) sizes.set(frameId, openFrameSize(held, sheetsNow(), open, sizes));
    }
    const positions = (await layout(target, sizes, pins)).map((p) => {
      // A pin's y is its middle (its x doesn't count: it sits on the edge).
      const node = target.nodes?.find((n) => n.id === p.id);
      return node && isPin(node) ? { ...p, y: p.y + PIN_DOT } : p;
    });
    spreadPins(target, positions);
    relaid.set(target.id, { ...target, nodes: (target.nodes ?? []).map((n) => ({ ...n, ...(positions.find((p) => p.id === n.id) ?? {}) })) });
    edits.push({ type: "moveNodes", sheet: target.id, positions });
    undo.push({ type: "moveNodes", sheet: target.id, positions: (target.nodes ?? []).map((n) => ({ id: n.id, x: n.x ?? 0, y: n.y ?? 0 })) });
  };
  // view.frames lists frames outside in; inner sheets go first.
  const frames = [...view.frames];
  const framesOn = (id: string): [string, string][] => frames.filter(([frameId]) => view.owner.get(frameId) === id).map(([frameId, f]) => [frameId, f.sheet]);
  for (const [, frame] of [...frames].reverse()) {
    const held = sheets.find((x) => x.id === frame.sheet);
    if (held) await tidySheet(held, framesOn(held.id));
  }
  await tidySheet(sheet, framesOn(sheet.id));
  return { edits, undo };
}

/** Room between two pins' tags on one edge. */
const PIN_SPACING = PIN_H + 8;

/**
 * A sheet's pins all sit on its edge, whatever column the layout put them in: two in different columns at one height
 * would land on each other. Down each edge in order, a pin too close to the one above moves down.
 */
function spreadPins(sheet: Sheet, positions: { id: string; x: number; y: number }[]) {
  for (const kind of ["sheet_input", "sheet_output"]) {
    const ids = new Set((sheet.nodes ?? []).filter((n) => n.settings.kind === kind).map((n) => n.id));
    const pins = positions.filter((p) => ids.has(p.id)).sort((a, b) => a.y - b.y);
    for (let i = 1; i < pins.length; i++) pins[i].y = Math.max(pins[i].y, pins[i - 1].y + PIN_SPACING);
  }
}
