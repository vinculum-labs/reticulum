// Turns a sheet into Svelte Flow nodes and edges.

import type { Edge, Node } from "@xyflow/svelte";
import type { Device, GlobalPos, GraphNode, MeUsage, Resource, Sheet, Wire } from "./types";
export type { MeUsage };
import { GATE, pinName, pinSlot, posKey, sheetPins } from "./types";
import { RESOURCE_VAR, formatRate, isSignal } from "./kinds";

export interface NodeData extends Record<string, unknown> {
  node: GraphNode;
  device?: Device;
  resource: Resource | null;
  rate: number;
  /** Moved over the last 5 minutes. */
  fiveMinutes: number;
  /** Why it moved nothing on its last attempt. */
  status?: string;
  /** For a sheet or copy node: the sheet it holds (or copies) and its pins, in order. */
  holder?: { sheet?: Sheet; inputs: HolderPin[]; outputs: HolderPin[]; problems: number };
  /** For a signal node: its output as last reported. */
  signal?: number;
  /** A wire is plugged into its gate (the gate pin shows only then, or on hover). */
  gateWired?: boolean;
  /** What is wrong with it, one error per line, as the server compiled the sheet. */
  problem?: string;
  /** A sheet node opened in place: drawn as a frame this big, its sheet's nodes inside it. */
  frame?: { width: number; height: number };
}

/** A card's header height and pin row height, as GraphNode draws them. */
export const CARD_HEADER = 34;
export const CARD_ROW = 18;
/** Room around a frame's contents. */
const FRAME_PAD = 24;
/** A card's size before Svelte Flow has measured it. */
const GUESS = { width: 160, height: 60 };

/** Where a frame's contents start below its top edge: under its header and pin rows. */
function frameTop(holder: { inputs: HolderPin[]; outputs: HolderPin[] }): number {
  return CARD_HEADER + Math.max(holder.inputs.length, holder.outputs.length, 1) * CARD_ROW + FRAME_PAD;
}

/**
 * A sheet node opened in place: the sheet inside it, and the offset from a node's spot in that sheet to its spot in
 * the frame (sheet x = position in the frame + dx).
 */
export interface Frame {
  sheet: string;
  dx: number;
  dy: number;
}

/** What the canvas shows: nodes and wires, the sheet each one is on, and the open frames by sheet node id. */
export interface FlowView {
  nodes: Node<NodeData>[];
  edges: Edge[];
  owner: Map<string, string>;
  frames: Map<string, Frame>;
}

/** One pin of a sheet or copy node: its name, the slot wires attach by, and what it carries (none: anything). */
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
      if (target && target.settings.kind !== "device" && !isSignal(target.settings.kind)) {
        if (!nodes.has(w.to)) nodes.set(w.to, resource);
        queue.push([w.to, resource]);
      }
    }
  };
  const flows = (r: Resource | undefined): r is Resource => !!r && r !== "redstone" && r !== "me";
  for (const n of sheet.nodes ?? []) {
    const s = n.settings;
    if (s.kind === "device") (s.outputs ?? []).forEach((o, i) => flows(o.resource) && emit(n.id, o.resource, i));
    // A typed pin into this sheet brings its resource in; a sheet node's typed outputs bring theirs out.
    if (s.kind === "sheet_input" && flows(s.resource)) {
      nodes.set(n.id, s.resource);
      emit(n.id, s.resource);
    }
    if (s.kind === "sheet" || s.kind === "copy") {
      const held = sheets.find((x) => x.id === s.sheet);
      for (const pin of holderPins(held, false)) if (flows(pin.resource)) emit(n.id, pin.resource, pin.slot);
    }
  }
  while (queue.length) {
    const [id, resource] = queue.shift()!;
    emit(id, resource);
  }
  return { wires, nodes };
}

/**
 * What a pin carries: a device node's input or output; a sheet or copy node's pin by slot (from the sheet it
 * holds, in {@code sheets}); a Sheet input's output or Sheet output's input. Null when it isn't fixed.
 */
export function pinResource(node: GraphNode | undefined, direction: "in" | "out", index: number, sheets: Sheet[] = []): Resource | null {
  const s = node?.settings;
  if (s?.kind === "device") return (direction === "in" ? s.inputs?.[index]?.resource : s.outputs?.[index]?.resource) ?? null;
  if (s?.kind === "sheet" || s?.kind === "copy") {
    const held = sheets.find((x) => x.id === s.sheet);
    return holderPins(held, direction === "in").find((p) => p.slot === index)?.resource ?? null;
  }
  if ((s?.kind === "sheet_input" && direction === "out") || (s?.kind === "sheet_output" && direction === "in")) return s.resource ?? null;
  return null;
}

/** Kinds whose inputs and outputs are numbered pins ("in:0", "out:1"); a sheet node's numbers are its pins' slots. */
function numbered(node: GraphNode | undefined): boolean {
  const kind = node?.settings.kind;
  return kind === "device" || kind === "sheet" || kind === "copy" || isSignal(kind);
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
): FlowView {
  const view: FlowView = { nodes: [], edges: [], owner: new Map(), frames: new Map() };
  const byKey = new Map(devices.map((d) => [posKey(d.pos), d]));
  const byId = new Map(sheets.map((x) => [x.id, x]));

  /**
   * Adds a sheet's nodes and wires (inside frame {@code parent} when given, shifted by {@code shift}); returns the
   * box its nodes cover, in sheet coordinates.
   */
  function build(at: Sheet, parent: string | undefined, shift: { dx: number; dy: number }, depth: number, seen: Set<string>) {
    const { nodes, edges } = sheetFlow(at, sheets, byKey, activity, selected, unit, wireStyle);
    // Frames first, inside out: a frame's size is its contents'.
    const sizeOf = new Map<string, { width: number; height: number }>();
    const inner: (() => void)[] = [];
    for (const n of nodes) {
      const s = n.data.node.settings;
      const held = s.kind === "sheet" ? byId.get(s.sheet) : undefined;
      if (!held || !open.has(n.id) || depth >= 8 || seen.has(held.id)) continue;
      const holder = n.data.holder!;
      const top = frameTop(holder);
      // Measure the contents without adding them yet: they go after this frame, which Svelte Flow needs first.
      const probe = contentBox(held, sheets, open, sizes, depth + 1, new Set([...seen, held.id]));
      const frame: Frame = { sheet: held.id, dx: probe.minX - FRAME_PAD, dy: probe.minY - top };
      view.frames.set(n.id, frame);
      const width = Math.max(probe.maxX - probe.minX + 2 * FRAME_PAD, 220);
      const height = top + probe.maxY - probe.minY + FRAME_PAD;
      n.data.frame = { width, height };
      sizeOf.set(n.id, { width, height });
      inner.push(() => build(held, n.id, frame, depth + 1, new Set([...seen, held.id])));
    }
    for (const n of nodes) {
      n.position = { x: n.position.x - shift.dx, y: n.position.y - shift.dy };
      if (parent) n.parentId = parent;
      view.owner.set(n.id, at.id);
      view.nodes.push(n);
    }
    for (const e of edges) {
      view.owner.set(e.id, at.id);
      view.edges.push(e);
    }
    for (const add of inner) add();
  }
  build(sheet, undefined, { dx: 0, dy: 0 }, 0, new Set([sheet.id]));
  return view;
}

/** The box a sheet's nodes cover, frames opened in it included (an empty sheet: a small box at 0, 0). */
function contentBox(
  sheet: Sheet,
  sheets: Sheet[],
  open: Set<string>,
  sizes: Map<string, { width: number; height: number }>,
  depth: number,
  seen: Set<string>,
): { minX: number; minY: number; maxX: number; maxY: number } {
  const list = sheet.nodes ?? [];
  if (!list.length) return { minX: 0, minY: 0, maxX: 200, maxY: 80 };
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const n of list) {
    let size = sizes.get(n.id) ?? GUESS;
    const s = n.settings;
    const held = s.kind === "sheet" ? sheets.find((x) => x.id === s.sheet) : undefined;
    if (held && open.has(n.id) && depth < 8 && !seen.has(held.id)) {
      const box = contentBox(held, sheets, open, sizes, depth + 1, new Set([...seen, held.id]));
      const holder = { inputs: holderPins(held, true), outputs: holderPins(held, false) };
      size = { width: Math.max(box.maxX - box.minX + 2 * FRAME_PAD, 220), height: frameTop(holder) + box.maxY - box.minY + FRAME_PAD };
    }
    const x = n.x ?? 0, y = n.y ?? 0;
    minX = Math.min(minX, x);
    minY = Math.min(minY, y);
    maxX = Math.max(maxX, x + size.width);
    maxY = Math.max(maxY, y + size.height);
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
    if (s.kind === "copy" || s.kind === "sheet") {
      const used = sheets.find((x) => x.id === s.sheet);
      holder = {
        sheet: used,
        inputs: holderPins(used, true),
        outputs: holderPins(used, false),
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
        resource: kinds.nodes.get(n.id) ?? null,
        rate: rates[n.id] ?? 0,
        fiveMinutes: activity.fiveMinutes[n.id] ?? 0,
        status: activity.status[n.id],
        holder,
        signal: isSignal(s.kind) ? (activity.signals[n.id] ?? 0) : undefined,
        gateWired: (sheet.wires ?? []).some((w) => w.to === n.id && (w.input ?? 0) === GATE) || undefined,
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
    const signal = isSignal(fromNode?.settings.kind) || pinResource(fromNode, "out", w.output ?? 0, sheets) === "redstone";
    const on = signal && (activity.signals[isSignal(fromNode?.settings.kind) ? w.from : `${w.from}/out/${w.output ?? 0}`] ?? 0) > 0;
    const pin = pinResource(fromNode, "out", w.output ?? 0, sheets);
    const color = signal || pin === "redstone" ? "var(--c-signal)" : pin === "me" ? "var(--c-me)" : resource ? RESOURCE_VAR[resource] : "var(--muted)";
    return {
      id: w.id,
      source: w.from,
      target: w.to,
      sourceHandle: sourceHandle(byId.get(w.from), w.output ?? 0),
      targetHandle: targetHandle(byId.get(w.to), w.input ?? 0),
      type: wireStyle,
      selected: selected.has(w.id),
      animated: rate > 0,
      label: [branchNumber.get(w.id), rate > 0 ? formatRate(rate, resource, unit) : undefined].filter((x) => x !== undefined).join(" · ") || undefined,
      style: signal
        ? `stroke: ${color}; stroke-width: ${on ? 2.5 : 1.5}px; stroke-dasharray: ${on ? "none" : "4 3"}; opacity: ${on ? 1 : 0.7}`
        : `stroke: ${fromNode?.settings.kind === "filter" && w.output === 1 ? "var(--bad)" : color}; stroke-width: ${rate > 0 ? 2.5 : 1.5}px`,
    };
  });
  return { nodes, edges };
}
