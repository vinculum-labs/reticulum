// Turns a sheet into Svelte Flow nodes and edges.

import type { Edge, Node } from "@xyflow/svelte";
import type { Binding, Device, GlobalPos, GraphNode, MeUsage, Resource, Sheet, Wire } from "./types";
export type { MeUsage };
import { GATE, pinName, pinSlot, posKey, sheetPins } from "./types";
import type { Layer, LayerMode } from "./state.svelte";
import { RESOURCE_VAR, formatRate, isSignal } from "./kinds";
import { inInstance, slotName } from "./blueprints";

/** How the layers are set. */
export interface LayerView {
  modes: Partial<Record<Layer, LayerMode>>;
}

export interface NodeData extends Record<string, unknown> {
  node: GraphNode;
  device?: Device;
  resource: Resource | null;
  rate: number;
  /** Moved over the last 5 minutes. */
  fiveMinutes: number;
  /** Why it moved nothing on its last attempt. */
  status?: string;
  /** For a sheet or instance node: the sheet it holds (or instances) and its pins, in order. */
  holder?: { sheet?: Sheet; inputs: HolderPin[]; outputs: HolderPin[]; problems: number };
  /** For a signal node: its output as last reported. */
  signal?: number;
  /** A wire is plugged into its gate (the gate pin shows only then, or on hover). */
  gateWired?: boolean;
  /** For a signal node: the inputs a wire is plugged into (an unwired "run" or "on" counts as always on). */
  wiredInputs?: number[];
  /** What is wrong with it, one error per line, as the server compiled the sheet. */
  problem?: string;
  /** A filter, rate limit, splitter or kit on a dimmed or hidden layer: drawn faint. */
  faded?: boolean;
  /**
   * A sheet node opened in place: drawn as a frame this big, its sheet's nodes inside it; its pins' handles sit on
   * its edges, level with the pins inside ({@code top}: from the frame's top edge).
   */
  frame?: { width: number; height: number; pins: Record<string, number> };
  /**
   * A blueprint's slot: the kind of block it takes; when an instance is open, whether a block fills it ({@code device}
   * is then that block) and how far it is turned.
   */
  slot?: { block?: string; filled?: boolean; turns?: number };
  /** A Level sensor on a blueprint reading a slot: that slot (with an instance open, {@code device} is the block filling it). */
  reads?: { name: string; block?: string };
}

/** A card's header height and pin row height, as GraphNode draws them. */
export const CARD_HEADER = 34;
export const CARD_ROW = 18;
/** An edge pin's size; a pin's y in its sheet is its middle (its x doesn't matter: it sits on the edge). */
export const PIN_W = 200;
export const PIN_H = 20;
/** From an edge pin's top to its dot (which sits on the wire's line, the pin's y: the label is centered on it); and the dot's size. */
export const PIN_DOT = 10;
export const PIN_KNOB = 10;
/** Room around a frame's contents, and between edge pins and the cards. */
const FRAME_PAD = 24;
const PIN_GAP = 40;
/** Where the contents start below a frame's top edge: under its header (a sheet node's) or its title line (the open sheet's). */
const FRAME_TOP = CARD_HEADER + FRAME_PAD;
const SHEET_TOP = 28 + FRAME_PAD;
/** A card's size before Svelte Flow has measured it. */
const GUESS = { width: 160, height: 60 };

/**
 * Where a signal wire's value is: a signal node's first output under its id, its others (a Sequencer's tracks) under
 * "<node>/out/<n>"; a sheet pin under its own id; a sheet or placed blueprint's output under the pin inside it (a
 * placed blueprint's by the instance's name for it); a device's redstone pin under "<node>/out/<n>".
 */
function signalKey(from: GraphNode | undefined, w: Wire, sheets: Sheet[]): string {
  const output = w.output ?? 0;
  if (!from) return w.from;
  const s = from.settings;
  if (isSignal(s.kind)) return output > 0 ? `${w.from}/out/${output}` : w.from;
  if (isPin(from)) return w.from;
  if (s.kind === "sheet" || s.kind === "instance") {
    const inner = sheets.find((x) => x.id === s.sheet);
    const pin = inner ? sheetPins(inner, false).find((p, i) => pinSlot(p, i) === output) : undefined;
    if (pin) return s.kind === "instance" ? inInstance(w.from, pin.id) : pin.id;
  }
  return `${w.from}/out/${output}`;
}

export function isPin(node: GraphNode): boolean {
  return node.settings.kind === "sheet_input" || node.settings.kind === "sheet_output";
}

/**
 * The top-left corner of a sheet's contents: cards by their corners, pins by their tops (their x doesn't count).
 * {@code spot} gives where each node is (as stored, or as just dragged).
 */
export function contentCorner(nodes: GraphNode[], spot: (n: GraphNode) => { x: number; y: number } = (n) => ({ x: n.x ?? 0, y: n.y ?? 0 })) {
  let x = Infinity;
  let y = Infinity;
  for (const n of nodes) {
    const at = spot(n);
    if (!isPin(n)) x = Math.min(x, at.x);
    y = Math.min(y, isPin(n) ? at.y - PIN_DOT : at.y);
  }
  return { x: x === Infinity ? 0 : x, y: y === Infinity ? 0 : y };
}

/**
 * How a sheet is laid out in a frame: its size, and the offset from a spot in the sheet to the same spot in the
 * frame (sheet x = position in the frame + dx). Room is kept on the left for input pins, on the right for outputs.
 */
interface SheetLayout {
  dx: number;
  dy: number;
  width: number;
  height: number;
}

function sheetLayout(
  sheet: Sheet,
  sheets: Sheet[],
  open: Set<string>,
  sizes: Map<string, { width: number; height: number }>,
  depth: number,
  seen: Set<string>,
  top: number,
): SheetLayout {
  const box = contentBox(sheet, sheets, open, sizes, depth, seen);
  // Pins' labels sit outside the frame; inside, only room for their wires to leave the edge.
  const left = sheetPins(sheet, true).length ? PIN_GAP : FRAME_PAD;
  const right = sheetPins(sheet, false).length ? PIN_GAP : FRAME_PAD;
  return {
    dx: box.minX - left,
    dy: box.minY - top,
    width: Math.max(box.maxX - box.minX + left + right, 240),
    height: top + box.maxY - box.minY + FRAME_PAD,
  };
}

/** How big a sheet node opened in place is drawn: its sheet's contents (and the frames open in it) with room around. */
export function openFrameSize(sheet: Sheet, sheets: Sheet[], open: Set<string>, sizes: Map<string, { width: number; height: number }>) {
  const layout = sheetLayout(sheet, sheets, open, sizes, 1, new Set([sheet.id]), FRAME_TOP);
  return { width: layout.width, height: layout.height };
}

/**
 * A sheet node opened in place: the sheet inside it, and the offset from a node's spot in that sheet to its spot in
 * the frame (sheet x = position in the frame + dx).
 */
export interface Frame {
  sheet: string;
  dx: number;
  dy: number;
  /** Where the frame sat on the canvas when built: drags are saved from absolute spots, since it may grow mid-drag. */
  ax: number;
  ay: number;
}

/**
 * What the canvas shows: nodes and wires, the sheet each one is on, the open frames by sheet node id, and the open
 * sheet's own frame (where its pins sit; none for the top sheet, which has no pins).
 */
export interface FlowView {
  nodes: Node<NodeData>[];
  edges: Edge[];
  owner: Map<string, string>;
  frames: Map<string, Frame>;
  sheetFrame?: { x: number; y: number; width: number; height: number };
}

/** One pin of a sheet or instance node: its name, the slot wires attach by, and what it carries (none: anything). */
export interface HolderPin {
  name: string;
  slot: number;
  resource?: Resource;
}

/** A sheet's pins as its node shows them: its Sheet input (or output) nodes in order, each with its slot. */
export function holderPins(sheet: Sheet | undefined, input: boolean): HolderPin[] {
  return sheetPins(sheet, input).map((pin, i) => {
    const s = pin.settings;
    return { name: pinName(pin, i), slot: pinSlot(pin, i), resource: s.kind === "sheet_input" || s.kind === "sheet_output" ? s.resource : undefined };
  });
}

/**
 * A sheet's pins as its node shows them, an untyped one with what it carries inside (worked out from what it is wired
 * to there), so the node's pin, its wires and the layers agree. Shown only: what may be wired to it is its type.
 */
export function carriedPins(sheet: Sheet | undefined, input: boolean, sheets: Sheet[], seen?: Set<string>): HolderPin[] {
  const pins = sheetPins(sheet, input);
  return holderPins(sheet, input).map((p, i) => (p.resource || !sheet ? p : { ...p, resource: pinCarries(sheet, pins[i], sheets, seen) }));
}

export interface Activity {
  rates: Record<string, number>;
  fiveMinutes: Record<string, number>;
  status: Record<string, string>;
  signals: Record<string, number>;
  me: Record<string, MeUsage>;
}

/** A splitter's outgoing wires in branch order: the engine's order (by order, then id). */
export function splitterBranches(sheet: Sheet, nodeId: string): Wire[] {
  return (sheet.wires ?? [])
    .filter((w) => w.from === nodeId)
    .sort((a, b) => (a.order ?? 0) - (b.order ?? 0) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
}

/** The devices whose outputs reach a node through the sheet's wires (not gate wires), nearest first. */
export function upstreamDevices(sheet: Sheet, nodeId: string): GlobalPos[] {
  const byId = new Map((sheet.nodes ?? []).map((n) => [n.id, n]));
  const found: GlobalPos[] = [];
  const seen = new Set<string>([nodeId]);
  let frontier = [nodeId];
  while (frontier.length) {
    const next: string[] = [];
    for (const id of frontier) {
      for (const w of sheet.wires ?? []) {
        if (w.to !== id || (w.input ?? 0) === GATE || seen.has(w.from)) continue;
        seen.add(w.from);
        const from = byId.get(w.from);
        if (!from) continue;
        if (from.settings.kind === "device") found.push(from.settings.device);
        else next.push(from.id);
      }
    }
    frontier = next;
  }
  return found;
}

/**
 * Which resource each wire carries: what its source or device output emits, passed on through filters, rate
 * limits and the like. Also returns a resource per node for coloring.
 */
export function resources(sheet: Sheet, sheets: Sheet[] = []): { wires: Map<string, Resource>; nodes: Map<string, Resource> } {
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
      if (wires.has(w.id) || (w.input ?? 0) === GATE) continue;
      wires.set(w.id, resource);
      const target = byId.get(w.to);
      // Passed on through filters and the like; not through a sheet or placed blueprint (nothing flows straight
      // across one: its outputs carry what their pins inside say).
      const holder = target?.settings.kind === "sheet" || target?.settings.kind === "instance";
      if (target && !holder && target.settings.kind !== "device" && !isSignal(target.settings.kind)) {
        if (!nodes.has(w.to)) nodes.set(w.to, resource);
        queue.push([w.to, resource]);
      }
    }
  };
  const flows = (r: Resource | undefined): r is Resource => !!r && r !== "redstone" && r !== "me";
  for (const n of sheet.nodes ?? []) {
    const s = n.settings;
    if (s.kind === "device") (s.outputs ?? []).forEach((o, i) => flows(o.resource) && emit(n.id, o.resource, i));
    // A pin into this sheet brings its resource in (an untyped one: what it is wired to takes); a sheet node's typed
    // outputs bring theirs out.
    const brought = s.kind === "sheet_input" ? pinCarries(sheet, n, sheets) : undefined;
    if (flows(brought)) {
      nodes.set(n.id, brought);
      emit(n.id, brought);
    }
    if (s.kind === "sheet" || s.kind === "instance") {
      const held = sheets.find((x) => x.id === s.sheet);
      for (const pin of carriedPins(held, false, sheets)) if (flows(pin.resource)) emit(n.id, pin.resource, pin.slot);
    }
  }
  while (queue.length) {
    const [id, resource] = queue.shift()!;
    emit(id, resource);
  }
  return { wires, nodes };
}

/** Nodes a resource passes through as it is (what is wired on beyond them tells what it is). */
const PASSING = new Set(["filter", "rate_limit", "splitter", "kit"]);

/**
 * What a sheet pin carries: its type, or for an untyped one ("anything") what it is wired to on this sheet takes or
 * gives — past filters, rate limits, splitters and kits, and into sheets and blueprints — so it and its wires show in
 * that color. {@code seen} stops pins wired to each other through sheets going round forever.
 */
export function pinCarries(sheet: Sheet, pin: GraphNode, sheets: Sheet[] = [], seen = new Set<string>()): Resource | undefined {
  const s = pin.settings;
  if (s.kind !== "sheet_input" && s.kind !== "sheet_output") return undefined;
  if (s.resource) return s.resource;
  if (seen.has(pin.id)) return undefined;
  seen.add(pin.id);
  const input = s.kind === "sheet_input";
  const byId = new Map((sheet.nodes ?? []).map((n) => [n.id, n]));
  const visited = new Set([pin.id]);
  let frontier = [pin.id];
  while (frontier.length) {
    const next: string[] = [];
    for (const id of frontier) {
      for (const w of sheet.wires ?? []) {
        if (input ? w.from !== id : w.to !== id) continue;
        if (input && id === pin.id && (w.input ?? 0) === GATE) return "redstone";
        // A gate wire into a filter (or the like) on the way switches it; it isn't what flows through.
        if ((w.input ?? 0) === GATE && id !== pin.id) continue;
        const other = byId.get(input ? w.to : w.from);
        if (!other) continue;
        if (isSignal(other.settings.kind)) return "redstone";
        const r = carriedAt(sheet, other, input ? "in" : "out", input ? (w.input ?? 0) : (w.output ?? 0), sheets, seen);
        if (r) return r;
        if (PASSING.has(other.settings.kind) && !visited.has(other.id)) {
          visited.add(other.id);
          next.push(other.id);
        }
      }
    }
    frontier = next;
  }
  return undefined;
}

/**
 * What a node's pin carries as shown: its type ({@link pinResource}), or for an untyped sheet pin — or a sheet or
 * blueprint node's untyped pin — what it is wired to inside.
 */
export function carriedAt(sheet: Sheet, node: GraphNode | undefined, direction: "in" | "out", index: number, sheets: Sheet[], seen = new Set<string>()): Resource | null {
  const typed = pinResource(node, direction, index, sheets);
  if (typed || !node) return typed;
  const s = node.settings;
  if (s.kind === "sheet_input" || s.kind === "sheet_output") return pinCarries(sheet, node, sheets, seen) ?? null;
  if (s.kind === "sheet" || s.kind === "instance") {
    const held = sheets.find((x) => x.id === s.sheet);
    return carriedPins(held, direction === "in", sheets, seen).find((p) => p.slot === index)?.resource ?? null;
  }
  return null;
}

/**
 * What a pin carries: a device node's input or output; a sheet or instance node's pin by slot (from the sheet it
 * holds, in {@code sheets}); a Sheet input's output or Sheet output's input. Null when it isn't fixed.
 */
export function pinResource(node: GraphNode | undefined, direction: "in" | "out", index: number, sheets: Sheet[] = []): Resource | null {
  const s = node?.settings;
  if (s?.kind === "device") return (direction === "in" ? s.inputs?.[index]?.resource : s.outputs?.[index]?.resource) ?? null;
  if (s?.kind === "sheet" || s?.kind === "instance") {
    const held = sheets.find((x) => x.id === s.sheet);
    return holderPins(held, direction === "in").find((p) => p.slot === index)?.resource ?? null;
  }
  if ((s?.kind === "sheet_input" && direction === "out") || (s?.kind === "sheet_output" && direction === "in")) return s.resource ?? null;
  return null;
}

/** Kinds whose inputs and outputs are numbered pins ("in:0", "out:1"); a sheet node's numbers are its pins' slots. */
function numbered(node: GraphNode | undefined): boolean {
  const kind = node?.settings.kind;
  return kind === "device" || kind === "sheet" || kind === "instance" || isSignal(kind);
}

export function sourceHandle(node: GraphNode | undefined, output: number): string {
  if (node?.settings.kind === "filter") return output === 1 ? "reject" : "match";
  if (numbered(node)) return `out:${output}`;
  return "out";
}

export function targetHandle(node: GraphNode | undefined, input: number): string {
  if (input === GATE) return "gate";
  return numbered(node) ? `in:${input}` : "in";
}

/** Reads a handle id back into an output or input number. */
export function handleIndex(handle: string | null | undefined): number {
  if (handle === "reject") return 1;
  if (handle === "gate") return GATE;
  const match = handle?.match(/^(?:in|out):(\d+)$/);
  return match ? Number(match[1]) : 0;
}

/** Problems in a sheet and every sheet inside it, for its node's badge. */
function problemsInside(sheets: Sheet[], sheet: Sheet): number {
  let count = 0;
  const todo = [sheet];
  const seen = new Set<string>();
  while (todo.length) {
    const at = todo.shift()!;
    if (seen.has(at.id)) continue;
    seen.add(at.id);
    count += at.errors?.length ?? 0;
    todo.push(...sheets.filter((x) => x.parent === at.id));
  }
  return count;
}

/**
 * Builds the canvas for a sheet. Sheet nodes listed in {@code open} show their sheet in place, as a frame with its
 * nodes and wires inside (and the frames open in those); {@code sizes} are the cards' measured sizes, for sizing frames.
 * {@code bindings}: an instance is open, the sheet being its blueprint; its slots show the blocks filling them.
 */
export function toFlow(
  sheet: Sheet,
  sheets: Sheet[],
  devices: Device[],
  activity: Activity,
  selected: Set<string>,
  unit: "t" | "s",
  wireStyle: string = "default",
  open: Set<string> = new Set(),
  sizes: Map<string, { width: number; height: number }> = new Map(),
  bindings?: Record<string, Binding>,
  layers: LayerView = { modes: {} },
): FlowView {
  const view: FlowView = { nodes: [], edges: [], owner: new Map(), frames: new Map() };
  const byKey = new Map(devices.map((d) => [posKey(d.pos), d]));
  const byId = new Map(sheets.map((x) => [x.id, x]));

  /** Sheet pins sit on the frame's edges: inputs on the left, outputs on the right, each at its own height. */
  function placePins(nodes: Node<NodeData>[], layout: SheetLayout, frame: boolean) {
    for (const n of nodes) {
      if (!isPin(n.data.node)) continue;
      const input = n.data.node.settings.kind === "sheet_input";
      n.type = "pin";
      // Its dot on the border, its label outside the frame.
      n.position = { x: layout.dx + (input ? -PIN_W : layout.width), y: (n.data.node.y ?? 0) - PIN_DOT };
      (n.data as NodeData & { pinSide?: string }).pinSide = frame ? "frame" : "sheet";
    }
  }

  /** Adds a sheet's nodes and wires, inside frame {@code parent} when given, shifted by {@code shift}. */
  function build(at: Sheet, parent: string | undefined, shift: { dx: number; dy: number }, depth: number, seen: Set<string>, layout: SheetLayout | null) {
    const { nodes, edges } = sheetFlow(at, sheets, byKey, activity, selected, unit, wireStyle, layers);
    if (at.blueprint) markSlots(nodes, at === sheet ? bindings : undefined, byKey);
    if (layout) placePins(nodes, layout, !!parent);
    // Frames first, inside out: a frame's size is its contents'.
    const inner: (() => void)[] = [];
    for (const n of nodes) {
      const s = n.data.node.settings;
      const held = s.kind === "sheet" ? byId.get(s.sheet) : undefined;
      if (!held || !open.has(n.id) || depth >= 8 || seen.has(held.id)) continue;
      // Measure the contents without adding them yet: they go after this frame, which Svelte Flow needs first.
      const within = sheetLayout(held, sheets, open, sizes, depth + 1, new Set([...seen, held.id]), FRAME_TOP);
      const frame: Frame = { sheet: held.id, dx: within.dx, dy: within.dy, ax: 0, ay: 0 };
      view.frames.set(n.id, frame);
      // The frame's own handles (for wires outside it) level with its pins inside.
      const pins: Record<string, number> = {};
      sheetPins(held, true).forEach((pin, i) => (pins[`in:${pinSlot(pin, i)}`] = (pin.y ?? 0) - within.dy));
      sheetPins(held, false).forEach((pin, i) => (pins[`out:${pinSlot(pin, i)}`] = (pin.y ?? 0) - within.dy));
      n.data.frame = { width: within.width, height: within.height, pins };
      // Svelte Flow grows a parent while its children are dragged past its edge (expandParent) through these.
      n.width = within.width;
      n.height = within.height;
      inner.push(() => build(held, n.id, frame, depth + 1, new Set([...seen, held.id]), within));
    }
    for (const n of nodes) {
      n.position = { x: n.position.x - shift.dx, y: n.position.y - shift.dy };
      if (parent) {
        n.parentId = parent;
        // Cards grow the frame as they are dragged past its edge; pins live outside it (their labels), so not them.
        n.expandParent = n.type !== "pin";
      }
      view.owner.set(n.id, at.id);
      view.nodes.push(n);
    }
    for (const e of edges) {
      view.owner.set(e.id, at.id);
      view.edges.push(e);
    }
    for (const add of inner) add();
  }
  // The open sheet is a frame too, its pins on its edges; the top sheet has no pins, so no frame.
  const layout = sheet.root ? null : sheetLayout(sheet, sheets, open, sizes, 0, new Set([sheet.id]), SHEET_TOP);
  if (layout) {
    view.sheetFrame = { x: layout.dx, y: layout.dy, width: layout.width, height: layout.height };
    view.nodes.push({
      id: `sheet-frame:${sheet.id}`,
      type: "sheetFrame",
      position: { x: layout.dx, y: layout.dy },
      selectable: false,
      draggable: false,
      connectable: false,
      deletable: false,
      focusable: false,
      zIndex: -1,
      data: { sheetId: sheet.id, name: sheet.name, blueprint: !!sheet.blueprint, width: layout.width, height: layout.height } as unknown as NodeData,
    });
  }
  build(sheet, undefined, { dx: 0, dy: 0 }, 0, new Set([sheet.id]), layout);
  // Each frame's spot on the canvas: its position plus its parents' (parents come before their children).
  const absolute = new Map<string, { x: number; y: number }>();
  for (const n of view.nodes) {
    const up = n.parentId ? absolute.get(n.parentId) : undefined;
    absolute.set(n.id, { x: n.position.x + (up?.x ?? 0), y: n.position.y + (up?.y ?? 0) });
  }
  for (const [id, frame] of view.frames) {
    const at = absolute.get(id);
    if (at) Object.assign(frame, { ax: at.x, ay: at.y });
  }
  return view;
}

/**
 * A blueprint's device nodes are slots: they show the kind of block they take, not the block they were laid out on.
 * With an instance open ({@code bindings}), each shows the block filling it, or that none does.
 */
function markSlots(nodes: Node<NodeData>[], bindings: Record<string, Binding> | undefined, byKey: Map<string, Device>) {
  for (const n of nodes) {
    const s = n.data.node.settings;
    if (s.kind === "level" && s.slot) {
      const slot = nodes.find((m) => m.id === s.slot)?.data.node;
      const binding = bindings?.[s.slot];
      n.data.reads = slot ? { name: slotName(slot), block: slot.settings.kind === "device" ? slot.settings.block : undefined } : { name: "a removed slot" };
      n.data.device = binding ? byKey.get(posKey(binding.device)) : undefined;
      continue;
    }
    if (s.kind !== "device") continue;
    const binding = bindings?.[n.id];
    n.data.slot = { block: s.block, filled: bindings ? !!binding : undefined, turns: binding?.turns };
    n.data.device = binding ? byKey.get(posKey(binding.device)) : undefined;
    // What moved through the blueprint's own nodes means nothing: it never runs. In an open instance, the activity
    // shown is the instance's (moved onto these ids), so it stays.
    if (!bindings) {
      n.data.rate = 0;
      n.data.fiveMinutes = 0;
      n.data.status = undefined;
    }
    if (bindings && !binding) n.data.problem = "No block fills this slot yet: drop a device from the list on it";
  }
}

/**
 * The box a sheet's nodes cover, frames opened in it included (an empty sheet: a small box at 0, 0). Pins count
 * only for its height: they sit on the edges.
 */
function contentBox(
  sheet: Sheet,
  sheets: Sheet[],
  open: Set<string>,
  sizes: Map<string, { width: number; height: number }>,
  depth: number,
  seen: Set<string>,
): { minX: number; minY: number; maxX: number; maxY: number } {
  const list = sheet.nodes ?? [];
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const n of list) {
    const x = n.x ?? 0, y = n.y ?? 0;
    if (isPin(n)) {
      minY = Math.min(minY, y - PIN_DOT);
      maxY = Math.max(maxY, y - PIN_DOT + PIN_H);
      continue;
    }
    let size = sizes.get(n.id) ?? GUESS;
    const s = n.settings;
    const held = s.kind === "sheet" ? sheets.find((x) => x.id === s.sheet) : undefined;
    if (held && open.has(n.id) && depth < 8 && !seen.has(held.id)) {
      size = sheetLayout(held, sheets, open, sizes, depth + 1, new Set([...seen, held.id]), FRAME_TOP);
    }
    minX = Math.min(minX, x);
    minY = Math.min(minY, y);
    maxX = Math.max(maxX, x + size.width);
    maxY = Math.max(maxY, y + size.height);
  }
  if (minX === Infinity) {
    // Only pins (or nothing): a small empty middle.
    minX = 0;
    maxX = 200;
  }
  if (minY === Infinity) {
    minY = 0;
    maxY = 80;
  }
  return { minX, minY, maxX, maxY };
}

/** One sheet's nodes and wires, at their spots in the sheet. */
function sheetFlow(
  sheet: Sheet,
  sheets: Sheet[],
  byKey: Map<string, Device>,
  activity: Activity,
  selected: Set<string>,
  unit: "t" | "s",
  wireStyle: string,
  layers: LayerView,
): { nodes: Node<NodeData>[]; edges: Edge[] } {
  const rates = activity.rates;
  const kinds = resources(sheet, sheets);
  const problems = new Map<string, string>();
  for (const error of sheet.errors ?? []) {
    if (error.node) problems.set(error.node, problems.has(error.node) ? `${problems.get(error.node)}\n${error.message}` : error.message);
  }
  const nodes: Node<NodeData>[] = (sheet.nodes ?? []).map((n) => {
    const s = n.settings;
    const device = "device" in s ? byKey.get(posKey(s.device)) : undefined;
    let holder: NodeData["holder"];
    if (s.kind === "instance" || s.kind === "sheet") {
      const used = sheets.find((x) => x.id === s.sheet);
      holder = {
        sheet: used,
        inputs: carriedPins(used, true, sheets),
        outputs: carriedPins(used, false, sheets),
        problems: s.kind === "sheet" && used ? problemsInside(sheets, used) : 0,
      };
    }
    return {
      id: n.id,
      type: "graph",
      position: { x: n.x ?? 0, y: n.y ?? 0 },
      selected: selected.has(n.id),
      data: {
        node: n,
        device,
        resource: kinds.nodes.get(n.id) ?? (isPin(n) ? pinCarries(sheet, n, sheets) : undefined) ?? null,
        rate: rates[n.id] ?? 0,
        fiveMinutes: activity.fiveMinutes[n.id] ?? 0,
        status: activity.status[n.id],
        holder,
        // A sheet pin carrying redstone shows its value too (it is worked out under its own id).
        signal: isSignal(s.kind) || (isPin(n) && pinCarries(sheet, n, sheets) === "redstone") ? (activity.signals[n.id] ?? 0) : undefined,
        gateWired: (sheet.wires ?? []).some((w) => w.to === n.id && (w.input ?? 0) === GATE) || undefined,
        wiredInputs: isSignal(s.kind) ? (sheet.wires ?? []).filter((w) => w.to === n.id && (w.input ?? 0) !== GATE).map((w) => w.input ?? 0) : undefined,
        // A sheet node also shows what is wrong inside its sheet (open it to see).
        problem: problems.get(n.id) ?? (holder?.problems ? `${holder.problems} problem${holder.problems === 1 ? "" : "s"} inside this sheet` : undefined),
      },
    };
  });
  const byId = new Map((sheet.nodes ?? []).map((n) => [n.id, n]));
  // Each splitter's branches numbered in the engine's order (by order, then id), shown on their wires.
  const branchNumber = new Map<string, number>();
  for (const n of sheet.nodes ?? []) {
    if (n.settings.kind !== "splitter") continue;
    splitterBranches(sheet, n.id).forEach((w, i) => branchNumber.set(w.id, i + 1));
  }
  const edges: Edge[] = (sheet.wires ?? []).map((w) => {
    const rate = rates[w.id] ?? 0;
    const resource = kinds.wires.get(w.id) ?? null;
    const fromNode = byId.get(w.from);
    // An untyped sheet pin's wires take what it carries from what else it is wired to.
    const pin = carriedAt(sheet, fromNode, "out", w.output ?? 0, sheets);
    const signal = isSignal(fromNode?.settings.kind) || pin === "redstone";
    const on = signal && (activity.signals[signalKey(fromNode, w, sheets)] ?? 0) > 0;
    const color = signal ? "var(--c-signal)" : pin === "me" ? "var(--c-me)" : resource ? RESOURCE_VAR[resource] : "var(--muted)";
    // Its layer. Hidden means hidden (wires coming and going with the selection looked like missing ones); only while
    // a new wire is drawn do hidden wires show faint (by their class).
    const layer: Layer | null = signal ? "redstone" : pin === "me" ? "me" : resource;
    const mode = (layer && layers.modes[layer]) || "show";
    const faint = mode === "dim" ? "; opacity: 0.18" : "";
    const hidden = mode === "hide";
    return {
      class: hidden ? "layer-hidden" : undefined,
      // Hidden means out of reach too: not taken by a box round its cards (and so not deleted unseen).
      selectable: !hidden,
      id: w.id,
      source: w.from,
      target: w.to,
      sourceHandle: sourceHandle(byId.get(w.from), w.output ?? 0),
      targetHandle: targetHandle(byId.get(w.to), w.input ?? 0),
      type: wireStyle,
      selected: !hidden && selected.has(w.id),
      animated: rate > 0,
      data: { signal, layer },
      // Labels are drawn apart from their wires (over the canvas), so they hide and dim with them here.
      label: hidden
        ? undefined
        : [branchNumber.get(w.id), rate > 0 ? formatRate(rate, resource, unit) : undefined].filter((x) => x !== undefined).join(" · ") || undefined,
      labelStyle: mode === "dim" ? "opacity: 0.18" : undefined,
      style: (signal
        ? `stroke: ${color}; stroke-width: ${on ? 2.5 : 1.5}px; stroke-dasharray: ${on ? "none" : "4 3"}; opacity: ${on ? 1 : 0.7}`
        : `stroke: ${fromNode?.settings.kind === "filter" && w.output === 1 ? "var(--bad)" : color}; stroke-width: ${rate > 0 ? 2.5 : 1.5}px`) + faint,
    };
  });
  for (const n of nodes) {
    // A flow node carries one resource: on a dimmed or hidden layer, it fades with its wires.
    const kind = n.data.node.settings.kind;
    const carried = kinds.nodes.get(n.id);
    if ((kind === "filter" || kind === "rate_limit" || kind === "splitter" || kind === "kit") && carried) {
      const m = layers.modes[carried as Layer];
      n.data.faded = m === "dim" || m === "hide";
    }
  }
  return { nodes, edges };
}
