// The editor's shared state. The server is the only authority: edits are sent, and the snapshot it pushes back
// replaces what is shown. Undo and redo send the inverse edits recorded with each change.

import { Connection } from "./protocol";
import type { Device, Edit, GlobalPos, GraphNode, NetworkSummary, Sheet, Snapshot, MeUsage, Face, Identify, PowerState } from "./types";
import { posKey } from "./types";
import { share } from "./share";
import { KIND_LABEL } from "./kinds";
import { rootSheet, sheetPath } from "./tree";

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
  socketOpen: false,
  inWorld: false,
  player: "",
  networks: [] as NetworkSummary[],
  current: null as string | null,
  snapshot: null as Snapshot | null,
  sheetId: null as string | null,
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
  /** Transmitted network signals, by name. */
  networkSignals: {} as Record<string, number>,
  /** Sheets a gate or redstone currently switches off (on their node, or on a sheet node above them). */
  sheetsOff: [] as string[],
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
    networkSignals: m.networkSignals ?? {},
    sheetsOff: m.sheetsOff ?? [],
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
  app.networkSignals = {};
  app.sheetsOff = [];
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
  if (!snapshot.sheets.some((s) => s.id === app.sheetId)) app.sheetId = rootSheet(snapshot)?.id ?? snapshot.sheets[0]?.id ?? null;
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
 * name, else the sub-sheet it uses or its pin name, else its kind.
 */
export function nodeTitle(node: GraphNode | undefined): string {
  if (!node) return "?";
  if (node.label) return node.label;
  const s = node.settings;
  if (s.kind === "device") return deviceName(deviceAt(posKey(s.device)));
  if (s.kind === "subsheet") return app.snapshot?.sheets.find((x) => x.id === s.sheet)?.name ?? "Missing sub-sheet";
  if (s.kind === "sheet") return app.snapshot?.sheets.find((x) => x.id === s.sheet)?.name ?? "Missing sheet";
  if ((s.kind === "sheet_input" || s.kind === "sheet_output") && s.name) return s.name;
  return KIND_LABEL[s.kind];
}

/** One place a device is used: a sheet (which opens it), or the network's redstone condition. */
export interface DeviceUse {
  label: string;
  sheetId?: string;
}

/**
 * Where each device is used, by its position key: every sheet with a node on it (a device, Level or redstone node)
 * or a redstone condition reading it, and the network's condition. A device absent here is unused.
 */
export function deviceUses(snapshot: Snapshot): Map<string, DeviceUse[]> {
  const uses = new Map<string, DeviceUse[]>();
  const add = (pos: GlobalPos | undefined, use: DeviceUse) => {
    if (!pos) return;
    const key = posKey(pos);
    const list = uses.get(key) ?? [];
    if (!list.some((u) => u.label === use.label && u.sheetId === use.sheetId)) list.push(use);
    uses.set(key, list);
  };
  for (const sheet of snapshot.sheets) {
    const use = { label: sheetPath(snapshot, sheet), sheetId: sheet.id };
    for (const node of sheet.nodes ?? []) {
      if ("device" in node.settings) add(node.settings.device as GlobalPos, use);
      add(node.redstone?.device, use);
    }
  }
  add(snapshot.redstone?.device, { label: "The network's redstone condition" });
  return uses;
}

/** Every signal name the network uses: transmitted anywhere on it, or seen live. */
export function signalNames(): string[] {
  const names = new Set<string>(Object.keys(app.networkSignals));
  for (const sheet of app.snapshot?.sheets ?? []) {
    for (const node of sheet.nodes ?? []) {
      if (node.settings.kind === "transmit" && node.settings.name) names.add(node.settings.name);
    }
  }
  return [...names].sort();
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

/** Moves a sheet (with everything in it) into another sheet. */
export function moveSheet(sheet: Sheet, parent: Sheet) {
  const from = sheet.parent;
  return edit([{ type: "moveSheet", sheet: sheet.id, parent: parent.id }], from ? [{ type: "moveSheet", sheet: sheet.id, parent: from }] : undefined, "move sheet");
}

/** Shows a sheet, with nothing selected (or the given nodes), and the inspector for it. */
export function openSheet(id: string, select: string[] = [], focus = false): void {
  app.sheetId = id;
  app.focusNode = focus && select.length ? select[0] : null;
  app.selectedNodes = select;
  app.selectedEdges = [];
  app.selectedDevice = null;
  app.panel = null;
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
