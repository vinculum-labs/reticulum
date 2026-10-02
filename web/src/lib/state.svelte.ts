// The editor's shared state. The server is the only authority: edits are sent, and the snapshot it pushes back
// replaces what is shown. Undo and redo send the inverse edits recorded with each change.

import { Connection } from "./protocol";
import type { Binding, CopySettings, Device, Edit, GlobalPos, GraphNode, NetworkSummary, Sheet, Snapshot, MeUsage, Face, Identify, PowerState } from "./types";
import { posKey } from "./types";
import { share } from "./share";
import { KIND_LABEL } from "./kinds";
import { rootSheet, sheetPath } from "./tree";
import { confirmDialog } from "./dialog.svelte";

export interface Toast {
  id: number;
  text: string;
  kind: "error" | "info";
}

interface Change {
  network: string;
  label: string;
  redo: Edit[];
  undo: Edit[];
}

export type RateUnit = "t" | "s";

function storedRateUnit(): RateUnit {
  try {
    return localStorage.getItem("reticulum.rateUnit") === "s" ? "s" : "t";
  } catch {
    return "t";
  }
}

/** How wires are drawn between pins: Svelte Flow's built-in edge types. */
export type WireStyle = "default" | "smoothstep" | "step" | "straight";

export const WIRE_STYLE_LABEL: Record<WireStyle, string> = {
  default: "Curved",
  smoothstep: "Rounded",
  step: "Square",
  straight: "Straight",
};

function storedWireStyle(): WireStyle {
  try {
    const stored = localStorage.getItem("reticulum.wireStyle");
    return stored && stored in WIRE_STYLE_LABEL ? (stored as WireStyle) : "default";
  } catch {
    return "default";
  }
}

/** Remembered per browser, like the rate unit. */
export function setWireStyle(style: WireStyle): void {
  app.wireStyle = style;
  try {
    localStorage.setItem("reticulum.wireStyle", style);
  } catch {
    // Private windows and blocked storage: the choice lasts until the page closes.
  }
}

/** How a sheet pin's name is drawn by its dot: underlined in its color, or as a tag pointing at the dot. */
export type PinStyle = "underline" | "tag";

export const PIN_STYLE_LABEL: Record<PinStyle, string> = {
  underline: "Underlined",
  tag: "Tags",
};

function storedPinStyle(): PinStyle {
  try {
    const stored = localStorage.getItem("reticulum.pinStyle");
    return stored && stored in PIN_STYLE_LABEL ? (stored as PinStyle) : "underline";
  } catch {
    return "underline";
  }
}

/** Remembered per browser, like the wire style. */
export function setPinStyle(style: PinStyle): void {
  app.pinStyle = style;
  try {
    localStorage.setItem("reticulum.pinStyle", style);
  } catch {
    // Private windows and blocked storage: the choice lasts until the page closes.
  }
}

function storedFrames(): string[] {
  try {
    const stored = JSON.parse(localStorage.getItem("reticulum.frames") ?? "[]");
    return Array.isArray(stored) ? stored.filter((x) => typeof x === "string") : [];
  } catch {
    return [];
  }
}

/** Opens a sheet node in place, as a frame showing its sheet, or closes it again. Remembered per browser. */
export function toggleFrame(nodeId: string): void {
  app.frames = app.frames.includes(nodeId) ? app.frames.filter((x) => x !== nodeId) : [...app.frames, nodeId];
  try {
    localStorage.setItem("reticulum.frames", JSON.stringify(app.frames));
  } catch {
    // Private windows and blocked storage: frames stay open until the page closes.
  }
}

export function setRateUnit(unit: RateUnit): void {
  app.rateUnit = unit;
  try {
    localStorage.setItem("reticulum.rateUnit", unit);
  } catch {
    // Private windows and blocked storage: the choice lasts until the page closes.
  }
}

export const app = $state({
  /** Rates are shown per tick by default, or per second. */
  rateUnit: storedRateUnit(),
  wireStyle: storedWireStyle(),
  pinStyle: storedPinStyle(),
  /** Sheet nodes shown open in place, as frames with their sheet inside (by node id). */
  frames: storedFrames(),
  socketOpen: false,
  inWorld: false,
  player: "",
  networks: [] as NetworkSummary[],
  current: null as string | null,
  snapshot: null as Snapshot | null,
  sheetId: null as string | null,
  /**
   * A blueprint copy open on the canvas: the sheet its node is on, and that node. The canvas shows its blueprint
   * ({@code sheetId}), read-only but for filling its slots.
   */
  copy: null as { sheet: string; node: string } | null,
  /** Amount moved in the last second, per node and wire id. */
  rates: {} as Record<string, number>,
  /** Amount moved over the last 5 minutes, per node and wire id. */
  fiveMinutes: {} as Record<string, number>,
  /** Ticks since each node or wire last moved anything. */
  ticksSinceMoved: {} as Record<string, number>,
  /** Why each node moved nothing on its last attempt (absent while it moves). */
  status: {} as Record<string, string>,
  /** Each signal node's output as of the last metrics update. */
  signals: {} as Record<string, number>,
  /** Sheets a gate or redstone currently switches off (on their node, or on a sheet node above them). */
  sheetsOff: [] as string[],
  /** Whether the network holding this one (if any) lets it run, as of the last metrics update. */
  heldOn: true,
  /** What running the network costs and what the controller holds, updated once a second. */
  power: null as PowerState | null,
  /** Channel use of each ME port node. */
  me: {} as Record<string, MeUsage>,
  selectedNodes: [] as string[],
  selectedEdges: [] as string[],
  selectedDevice: null as string | null,
  /** A node to bring into view once its sheet is on the canvas (opened from the dashboard). */
  focusNode: null as string | null,
  /** A node that pulses briefly to draw the eye (one picked from the sheet's errors). */
  flashNode: null as string | null,
  /** A panel shown in place of the inspector ("network" = network settings). */
  panel: null as "network" | "identified" | "dashboard" | null,
  /** How long the network's ticks take, in ms (last 200 ticks). */
  tick: null as { average: number; longest: number } | null,
  busy: 0,
  toasts: [] as Toast[],
  undo: [] as Change[],
  redo: [] as Change[],
});

let toastSeq = 0;

export function toast(text: string, kind: Toast["kind"] = "error"): void {
  const id = ++toastSeq;
  app.toasts.push({ id, text, kind });
  setTimeout(() => {
    app.toasts = app.toasts.filter((t) => t.id !== id);
  }, kind === "error" ? 7000 : 3000);
}

const connection = new Connection(onMessage, (open) => {
  app.socketOpen = open;
  if (!open) app.inWorld = false;
});

export function start(): void {
  connection.connect();
}

async function onMessage(m: any): Promise<void> {
  switch (m.type) {
    case "hello":
      app.inWorld = m.connected;
      if (m.connected) await loadNetworks(m.focus ?? hashNetwork());
      break;
    case "connected":
      app.inWorld = true;
      // The server dropped this page's subscription when the player left; subscribe again, even to the same network.
      await loadNetworks(app.current ?? hashNetwork(), true);
      break;
    case "disconnected":
      app.inWorld = false;
      break;
    case "focus":
      // A network the page hasn't seen yet (a new controller): reload the list first so its tab exists.
      if (m.network && !app.networks.some((n) => n.id === m.network)) await loadNetworks(m.network);
      else if (m.network) await selectNetwork(m.network);
      break;
    case "snapshot":
      if (m.network.id === app.current) setSnapshot(m.network);
      break;
    case "networks":
      app.networks = m.networks;
      if (app.current && !app.networks.some((n) => n.id === app.current)) {
        app.current = null;
        app.snapshot = null;
      }
      if (!app.current && app.networks.length) await selectNetwork(app.networks[0].id);
      break;
    case "metrics":
      if (m.network === app.current) {
        // Held while a node is being dragged, so the drag isn't interrupted by redraws; the latest is applied on drop.
        if (holdingMetrics) pendingMetrics = m;
        else applyMetrics(m);
      }
      break;
    case "removed":
      if (m.network === app.current) {
        app.current = null;
        app.snapshot = null;
        toast("You no longer have access to that network", "info");
      }
      await loadNetworks(null);
      break;
  }
}

let holdingMetrics = false;
let pendingMetrics: any = null;

/** Holds metrics updates (while dragging) or releases them, applying the newest one held. */
export function holdMetrics(hold: boolean) {
  holdingMetrics = hold;
  if (!hold && pendingMetrics) {
    const m = pendingMetrics;
    pendingMetrics = null;
    if (m.network === app.current) applyMetrics(m);
  }
}

/** Replaces a piece of state only when its contents changed, so what reads it doesn't redo its work for nothing. */
function changed(current: unknown, next: unknown): boolean {
  return current !== next && JSON.stringify(current) !== JSON.stringify(next);
}

function applyMetrics(m: any) {
  const next = {
    rates: m.perSecond ?? {},
    fiveMinutes: m.fiveMinutes ?? {},
    ticksSinceMoved: m.ticksSinceMoved ?? {},
    status: m.status ?? {},
    signals: m.signals ?? {},
    sheetsOff: m.sheetsOff ?? [],
    heldOn: m.heldOn ?? true,
    me: m.me ?? {},
    power: m.power ?? null,
    tick: m.tick ?? null,
  };
  for (const key of Object.keys(next) as (keyof typeof next)[]) {
    if (changed(app[key], next[key])) (app as any)[key] = next[key];
  }
}

function hashNetwork(): string | null {
  const match = location.hash.match(/network=([0-9a-f-]+)/);
  return match ? match[1] : null;
}

/** Loads the network list and shows {@code focus} (else the current or first network); {@code resubscribe} even if shown. */
export async function loadNetworks(focus: string | null, resubscribe = false): Promise<void> {
  try {
    const r = await connection.send({ op: "networks" });
    app.networks = r.networks;
    app.player = r.player;
    const target = focus && app.networks.some((n) => n.id === focus) ? focus : app.current ?? app.networks[0]?.id ?? null;
    if (target && (target !== app.current || resubscribe)) await selectNetwork(target);
  } catch (e) {
    toast((e as Error).message);
  }
}

export async function selectNetwork(id: string): Promise<void> {
  if (app.current && app.current !== id) connection.send({ op: "unsubscribe", network: app.current }).catch(() => {});
  app.current = id;
  history.replaceState(null, "", `#network=${id}`);
  app.rates = {};
  app.fiveMinutes = {};
  app.ticksSinceMoved = {};
  app.status = {};
  app.signals = {};
  app.sheetsOff = [];
  app.heldOn = true;
  app.me = {};
  app.selectedNodes = [];
  app.selectedEdges = [];
  app.selectedDevice = null;
  try {
    const r = await connection.send({ op: "subscribe", network: id });
    if (app.current === id) setSnapshot(r.network);
  } catch (e) {
    toast((e as Error).message);
  }
}

function setSnapshot(snapshot: Snapshot): void {
  if (snapshot.id !== app.current) return;
  // Unchanged parts keep their objects, so after an edit only what it changed re-renders.
  const merged = app.snapshot && app.snapshot.id === snapshot.id ? share(app.snapshot, snapshot) : snapshot;
  if (merged !== app.snapshot) app.snapshot = merged;
  const summary = app.networks.find((n) => n.id === snapshot.id);
  if (summary) Object.assign(summary, { name: snapshot.name, running: snapshot.running, enabled: snapshot.enabled });
  if (!snapshot.sheets.some((s) => s.id === app.sheetId)) openSheet(rootSheet(snapshot)?.id ?? snapshot.sheets[0]?.id ?? "");
  // A copy open on the canvas that is gone (deleted, turned into a sheet): back to its sheet.
  else if (app.copy && !openCopyNode()) openSheet(snapshot.sheets.some((s) => s.id === app.copy!.sheet) ? app.copy.sheet : (rootSheet(snapshot)?.id ?? ""));
}

// ---------------------------------------------------------------- edits

let queue: Promise<unknown> = Promise.resolve();

/** Runs edit requests one after another, in the order they were made. */
function serially<T>(task: () => Promise<T>): Promise<T> {
  const run = queue.then(task, task);
  queue = run.catch(() => {});
  return run;
}

/** Bumped when an edit is refused, so the canvas redraws from the snapshot (undoing what Svelte Flow did locally). */
export const resync = $state({ count: 0 });

/** Node versions sent but not yet answered: a second quick edit builds on these, not on the older snapshot. */
const sentNodes = new Map<string, GraphNode>();

/**
 * Sends edits; returns the server's per-edit results (created ids) or null on failure. {@code network} is where
 * they go (the network shown when not given): a confirm answered after switching networks still acts on the one
 * it asked about.
 */
export function edit(edits: Edit[], undo?: Edit[], label = "change", network = app.current): Promise<unknown[] | null> {
  if (!network || edits.length === 0) return Promise.resolve(null);
  const nodes: GraphNode[] = [];
  for (const e of edits) {
    if ((e.type === "updateNode" || e.type === "addNode") && e.node) {
      sentNodes.set((e.node as GraphNode).id, e.node as GraphNode);
      nodes.push(e.node as GraphNode);
    }
  }
  app.busy++;
  return serially(async () => {
    try {
      const r = await connection.send({ op: "edit", network, edits });
      if (undo) {
        app.undo.push({ network, label, redo: edits, undo });
        if (app.undo.length > 100) app.undo.shift();
        app.redo = [];
      }
      return r.results as unknown[];
    } catch (e) {
      toast((e as Error).message);
      resync.count++;
      return null;
    } finally {
      for (const n of nodes) if (sentNodes.get(n.id) === n) sentNodes.delete(n.id);
      app.busy--;
    }
  });
}

/**
 * A node edit made from {@code node} (as the snapshot shows it) turned into {@code next}, rebased onto the version
 * last sent for that node if its answer hasn't come back yet. Only the fields this edit changed are taken from
 * {@code next}, so typing a label and then clicking a checkbox keeps both. Returns the node to send, and the one
 * to restore on undo.
 */
export function rebase(node: GraphNode, next: GraphNode): { after: GraphNode; before: GraphNode } {
  const before = sentNodes.get(node.id) ?? node;
  if (before === node) return { after: next, before };
  const after: any = { ...before };
  for (const key of Object.keys(next) as (keyof GraphNode)[]) {
    if (key === "settings") continue;
    if (!same(next[key], node[key])) after[key] = next[key];
  }
  if (!same(next.settings, node.settings)) {
    const settings: any = { ...before.settings };
    for (const key of Object.keys(next.settings)) {
      if (!same((next.settings as any)[key], (node.settings as any)[key])) settings[key] = (next.settings as any)[key];
    }
    after.settings = settings;
  }
  return { after, before };
}

function same(a: unknown, b: unknown): boolean {
  return a === b || JSON.stringify(a) === JSON.stringify(b);
}

/** Undo and redo put a change back on its stack when the server refuses it, so it isn't lost. */
export function undo(): Promise<void> {
  return serially(async () => {
    const change = app.undo.pop();
    if (!change) return;
    if (change.network !== app.current) {
      app.undo = [];
      return;
    }
    app.busy++;
    try {
      await connection.send({ op: "edit", network: change.network, edits: change.undo });
      app.redo.push(change);
    } catch (e) {
      app.undo.push(change);
      toast(`Undo failed: ${(e as Error).message}`);
      resync.count++;
    } finally {
      app.busy--;
    }
  });
}

export function redo(): Promise<void> {
  return serially(async () => {
    const change = app.redo.pop();
    if (!change || change.network !== app.current) return;
    app.busy++;
    try {
      await connection.send({ op: "edit", network: change.network, edits: change.redo });
      app.undo.push(change);
    } catch (e) {
      app.redo.push(change);
      toast(`Redo failed: ${(e as Error).message}`);
      resync.count++;
    } finally {
      app.busy--;
    }
  });
}

export function request(op: string, payload: Record<string, unknown> = {}): Promise<any> {
  return connection.send({ op, network: app.current, ...payload });
}

// ---------------------------------------------------------------- lookups

export function currentSheet(): Sheet | null {
  return app.snapshot?.sheets.find((s) => s.id === app.sheetId) ?? null;
}

/** The sheet a node or wire is on (node and wire ids are unique across the network). */
export function sheetHolding(id: string): Sheet | undefined {
  return app.snapshot?.sheets.find((s) => s.nodes?.some((n) => n.id === id) || s.wires?.some((w) => w.id === id));
}

export function deviceAt(key: string): Device | undefined {
  return app.snapshot?.devices.find((d) => posKey(d.pos) === key);
}

const OFFSET: Record<string, [number, number, number]> = {
  down: [0, -1, 0],
  up: [0, 1, 0],
  north: [0, 0, -1],
  south: [0, 0, 1],
  west: [-1, 0, 0],
  east: [1, 0, 0],
};
const OPPOSITE: Record<string, Face> = { down: "up", up: "down", north: "south", south: "north", west: "east", east: "west" };

/** The device on one side of a connector (for an ME port: the AE2 cable or machine there). */
export function deviceBeside(connectorId: string, side: string): Device | undefined {
  const c = app.snapshot?.connectors.find((x) => x.id === connectorId);
  if (!c) return undefined;
  const [dx, dy, dz] = OFFSET[side] ?? [0, 0, 0];
  const at = `${c.pos.dimension}|${[c.pos.pos[0] + dx, c.pos.pos[1] + dy, c.pos.pos[2] + dz].join(",")}`;
  return app.snapshot?.devices.find((d) => posKey(d.pos) === at);
}


/** Shows a device's names (and face names) in the inspector. */
/**
 * Shows a device's names in the side panel. The canvas selection is left alone: clearing it would have the canvas
 * report it again and close the device straight away. The device covers a selected node until the selection changes.
 */
export function openDevice(device: Device) {
  app.selectedDevice = posKey(device.pos);
  app.panel = null;
}

function sameIdentify(a: Identify, b: Identify): boolean {
  return (
    a.kind === b.kind &&
    (a.connector ?? "") === (b.connector ?? "") &&
    (a.device ? posKey(a.device) : "") === (b.device ? posKey(b.device) : "") &&
    (a.face ?? "") === (b.face ?? "")
  );
}

/** Whether a connector, device or face is lit up in the world. */
export function isIdentified(target: Identify): boolean {
  return (app.snapshot?.identified ?? []).some((x) => sameIdentify(x, target));
}

/** Every identify on a device or one of its faces. */
export function deviceIdentifies(device: Device): Identify[] {
  const key = posKey(device.pos);
  return (app.snapshot?.identified ?? []).filter((x) => x.kind !== "connector" && x.device && posKey(x.device) === key);
}

/** Lights a connector, device or face up in the world (or turns it off); a color picks its color. */
export function setIdentify(target: Identify, on: boolean, color?: number) {
  return setIdentifies([target], on, color);
}

/**
 * Lights several things up or turns them off, as one undoable change. Undo puts each back as it was: off, or on in
 * its old color (so undoing a color change keeps it lit).
 */
export function setIdentifies(targets: Identify[], on: boolean, color?: number) {
  const edits: Edit[] = [];
  const undo: Edit[] = [];
  for (const target of targets) {
    const { label: _label, color: _color, ...bare } = target;
    const existing = (app.snapshot?.identified ?? []).find((x) => sameIdentify(x, target));
    edits.push({ type: "identify", ...bare, ...(color !== undefined ? { color } : {}), on });
    undo.push(existing ? { type: "identify", ...bare, color: existing.color, on: true } : { type: "identify", ...bare, on: false });
  }
  return edit(edits, undo, on ? "identify" : "stop identifying");
}

export function toggleIdentify(target: Identify) {
  return setIdentify(target, !isIdentified(target));
}

export function deviceName(device: Device | undefined): string {
  return device ? device.name || device.defaultName : "Unknown device";
}

/**
 * What a node is called everywhere (its card, the inspector, the dashboard, wires): its label, else its device's
 * name, else the blueprint it uses or its pin name, else its kind.
 */
export function nodeTitle(node: GraphNode | undefined): string {
  if (!node) return "?";
  if (node.label) return node.label;
  const s = node.settings;
  if (s.kind === "device") return deviceName(deviceAt(posKey(s.device)));
  if (s.kind === "copy") return app.snapshot?.sheets.find((x) => x.id === s.sheet)?.name ?? "Missing blueprint";
  if (s.kind === "network") return app.networks.find((n) => n.id === s.network)?.name ?? "Another network";
  if (s.kind === "sheet") return app.snapshot?.sheets.find((x) => x.id === s.sheet)?.name ?? "Missing sheet";
  if ((s.kind === "sheet_input" || s.kind === "sheet_output") && s.name) return s.name;
  return KIND_LABEL[s.kind];
}

/**
 * One place a device is used: a sheet (which opens it, the node selected).
 * {@code home}: its device node, where it lives (one per device; blueprints' copies aside).
 */
export interface DeviceUse {
  label: string;
  sheetId?: string;
  nodeId?: string;
  home?: boolean;
}

/**
 * Where each device is used, by its position key: every sheet with a node on it (its device node, or a Level node
 * reading it). A device absent here is unused.
 */
export function deviceUses(snapshot: Snapshot): Map<string, DeviceUse[]> {
  const uses = new Map<string, DeviceUse[]>();
  const add = (pos: GlobalPos | undefined, use: DeviceUse) => {
    if (!pos) return;
    const key = posKey(pos);
    const list = uses.get(key) ?? [];
    if (!list.some((u) => u.label === use.label && u.sheetId === use.sheetId && !!u.home === !!use.home)) list.push(use);
    uses.set(key, list);
  };
  for (const sheet of snapshot.sheets) {
    const label = sheetPath(snapshot, sheet);
    for (const node of sheet.nodes ?? []) {
      const s = node.settings;
      if (s.kind === "device") add(s.device, { label, sheetId: sheet.id, nodeId: node.id, home: !sheet.blueprint });
      else if ("device" in s) add(s.device as GlobalPos, { label, sheetId: sheet.id, nodeId: node.id });
      // A block filling a slot of a copy lives in that copy.
      if (s.kind === "copy" && !sheet.blueprint) {
        const copyLabel = `${label} › ${nodeTitle(node)}`;
        for (const binding of Object.values(s.bindings ?? {})) add(binding.device, { label: copyLabel, sheetId: sheet.id, nodeId: node.id, home: true });
      }
    }
  }
  return uses;
}

/** Switches a sheet on or off (its sheet node's switch; the network's, for the root), undoably. */
export function setSheetEnabled(sheet: Sheet, on: boolean) {
  return edit([{ type: "updateSheet", sheet: sheet.id, enabled: on }], [{ type: "updateSheet", sheet: sheet.id, enabled: sheet.enabled !== false }], "sheet on/off");
}

/**
 * Makes a new sheet inside {@code parent} (a sheet node there, at {@code at}), opening it unless told not to.
 * Returns its id.
 */
export async function createSheet(parent: Sheet | null, name: string, at?: { x: number; y: number }, open = true): Promise<string | null> {
  const results = await edit([
    { type: "createSheet", name, ...(parent ? { parent: parent.id } : {}), ...(at ? { x: Math.round(at.x), y: Math.round(at.y) } : {}) },
  ]);
  const id = (results?.[0] as string | undefined) ?? null;
  if (id && open) openSheet(id);
  return id;
}

/** Adds a pin to a sheet's edge (an input on the left, an output on the right), below the pins already there. */
export async function addSheetPin(sheet: Sheet, input: boolean) {
  const kind = input ? "sheet_input" : "sheet_output";
  const same = (sheet.nodes ?? []).filter((n) => n.settings.kind === kind);
  const y = same.length ? Math.max(...same.map((n) => n.y ?? 0)) + 40 : Math.min(0, ...(sheet.nodes ?? []).map((n) => n.y ?? 0)) + 30;
  const node: GraphNode = { id: uuid(), settings: { kind, name: "" }, x: 0, y: Math.round(y) };
  const ok = await edit([{ type: "addNode", sheet: sheet.id, node }], [{ type: "removeNode", sheet: sheet.id, node: node.id }], input ? "add input pin" : "add output pin");
  if (ok) {
    app.selectedNodes = [node.id];
    app.selectedEdges = [];
  }
}

/**
 * Moves a sheet (with everything in it) into another sheet. The wires on its node in the old parent drop, so when
 * there are any it asks first, naming what they connect to; undo puts them back.
 */
export async function moveSheet(sheet: Sheet, parent: Sheet) {
  const from = app.snapshot?.sheets.find((s) => s.id === sheet.parent);
  const network = app.current;
  const dropped = from ? (from.wires ?? []).filter((w) => w.from === sheet.node || w.to === sheet.node) : [];
  if (dropped.length) {
    const others = [...new Set(dropped.map((w) => nodeTitle(from!.nodes?.find((n) => n.id === (w.from === sheet.node ? w.to : w.from)))))];
    const ok = await confirmDialog({
      title: `Move “${sheet.name}” into “${parent.name}”?`,
      message: `${dropped.length === 1 ? "Its wire" : `Its ${dropped.length} wires`} in “${from!.name}” will be removed: to ${others.join(", ")}.`,
      confirm: "Move",
    });
    if (!ok || app.current !== network) return null;
  }
  const undo: Edit[] = from ? [{ type: "moveSheet", sheet: sheet.id, parent: from.id }, ...dropped.map((wire) => ({ type: "connect", sheet: from.id, wire }) as Edit)] : [];
  return edit([{ type: "moveSheet", sheet: sheet.id, parent: parent.id }], from ? undo : undefined, "move sheet");
}

/** Shows a sheet, with nothing selected (or the given nodes), and the inspector for it. */
export function openSheet(id: string, select: string[] = [], focus = false): void {
  app.sheetId = id;
  app.copy = null;
  app.focusNode = focus && select.length ? select[0] : null;
  app.selectedNodes = select;
  app.selectedEdges = [];
  app.selectedDevice = null;
  app.panel = null;
}

/** Opens a blueprint copy: its blueprint on the canvas, with the blocks filling its slots. */
export function openCopy(sheetId: string, node: string): void {
  const held = app.snapshot?.sheets.find((s) => s.id === sheetId)?.nodes?.find((n) => n.id === node);
  if (held?.settings.kind !== "copy") return;
  openSheet(held.settings.sheet);
  app.copy = { sheet: sheetId, node };
}

/** The copy open on the canvas: the sheet it is on, its node and its blueprint (null when none, or it is gone). */
export function openCopyNode(): { sheet: Sheet; node: GraphNode & { settings: CopySettings }; blueprint: Sheet } | null {
  const at = app.copy;
  const sheet = at && app.snapshot?.sheets.find((s) => s.id === at.sheet);
  const node = sheet?.nodes?.find((n) => n.id === at!.node);
  if (!sheet || node?.settings.kind !== "copy") return null;
  const blueprint = app.snapshot?.sheets.find((s) => s.id === (node.settings as CopySettings).sheet);
  return blueprint ? { sheet, node: node as GraphNode & { settings: CopySettings }, blueprint } : null;
}

/**
 * Changes which blocks fill a copy's slots, undoably. {@code change} gets the fillings as last sent (an earlier fill
 * may not be back from the server yet) and returns the new ones.
 */
export function setBindings(
  sheet: Sheet,
  node: GraphNode & { settings: CopySettings },
  change: (bindings: Record<string, Binding>) => Record<string, Binding>,
  label = "fill slot",
) {
  const before = (sentNodes.get(node.id) ?? node) as GraphNode & { settings: CopySettings };
  const next = { ...before, settings: { ...before.settings, bindings: change(before.settings.bindings ?? {}) } };
  return edit([{ type: "updateNode", sheet: sheet.id, node: next }], [{ type: "updateNode", sheet: sheet.id, node: before }], label);
}

/** Shows the node an error is about: selected, brought into view and pulsing for a moment. */
export function showNode(sheetId: string, node: string): void {
  openSheet(sheetId, [node], true);
  app.flashNode = null;
  queueMicrotask(() => (app.flashNode = node));
  setTimeout(() => {
    if (app.flashNode === node) app.flashNode = null;
  }, 2500);
}

/** An identify's color as CSS (the default yellow when none is set). */
export function identifyHex(color: number | undefined): string {
  return `#${(color ?? 0xffc830).toString(16).padStart(6, "0")}`;
}

/** Whether a device matches a search: every word must appear in its name, default name, block id or position. */
export function matchesDevice(device: Device, query: string): boolean {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (!words.length) return true;
  const text = `${deviceName(device)} ${device.defaultName} ${device.block} ${device.pos.pos.join(" ")}`.toLowerCase();
  return words.every((w) => text.includes(w));
}

export function nodeById(sheet: Sheet | null, id: string): GraphNode | undefined {
  return sheet?.nodes?.find((n) => n.id === id);
}

export function uuid(): string {
  return crypto.randomUUID();
}
