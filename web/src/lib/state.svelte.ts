// The editor's shared state. The server is the only authority: edits are sent, and the snapshot it pushes back
// replaces what is shown. Undo and redo send the inverse edits recorded with each change.

import { Connection } from "./protocol";
import type { Binding, InstanceSettings, Device, Edit, GlobalPos, GraphNode, NetworkSummary, Sheet, Snapshot, MeUsage, Face, Identify, PowerState } from "./types";
import { posKey } from "./types";
import { share } from "./share";
import { KIND_LABEL } from "./kinds";
import { descendants, rootSheet, sheetPath } from "./tree";
import { confirmDialog, promptDialog } from "./dialog.svelte";
import { allSlotKeys, instancesOf, fillOne, inInstance, slotName } from "./blueprints";
import { references } from "./templates";

export interface Toast {
  id: number;
  text: string;
  kind: "error" | "info";
  /** A button on it, e.g. to go to what it is about. */
  action?: { label: string; run: () => void };
}

interface Change {
  network: string;
  label: string;
  redo: Edit[];
  undo: Edit[];
  /** Its last sending got no answer: the game may have done it. Refused when sent again, it was. */
  uncertain?: boolean;
}

export type RateUnit = "t" | "s";

function storedRateUnit(): RateUnit {
  try {
    return localStorage.getItem("reticulum.rateUnit") === "s" ? "s" : "t";
  } catch {
    return "t";
  }
}

/** The canvas's layers: one per kind of wire (signal wires are "redstone"). */
export type Layer = "item" | "fluid" | "energy" | "chemical" | "redstone" | "me";
export const LAYERS: Layer[] = ["item", "fluid", "energy", "chemical", "redstone", "me"];
/** Shown; drawn faint (still clickable); or hidden (out of sight and reach, its pins dimmed). */
export type LayerMode = "show" | "dim" | "hide";

function storedLayers(): Record<Layer, LayerMode> {
  const all = Object.fromEntries(LAYERS.map((l) => [l, "show"])) as Record<Layer, LayerMode>;
  try {
    const stored = JSON.parse(localStorage.getItem("reticulum.layers") ?? "{}");
    for (const l of LAYERS) if (stored[l] === "dim" || stored[l] === "hide") all[l] = stored[l];
  } catch {
    // Nothing remembered (or storage refused): all shown.
  }
  return all;
}

/** Remembered per browser: a view of the sheet, not part of the network. */
export function setLayers(layers: Partial<Record<Layer, LayerMode>>): void {
  app.layers = { ...app.layers, ...layers };
  try {
    localStorage.setItem("reticulum.layers", JSON.stringify(app.layers));
  } catch {
    // Not remembered.
  }
}

/** Only one layer shown, the rest hidden (or all shown again with {@code null}). */
export function focusLayer(layer: Layer | null): void {
  setLayers(Object.fromEntries(LAYERS.map((l) => [l, layer === null || l === layer ? "show" : "hide"])) as Record<Layer, LayerMode>);
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

/** Each sheet's pan and zoom as last left (by sheet id; "instance:<node id>" for an open blueprint instance), per browser. */
type SavedView = { x: number; y: number; zoom: number };

function storedViews(): Record<string, SavedView> {
  try {
    const stored = JSON.parse(localStorage.getItem("reticulum.views") ?? "{}");
    return stored && typeof stored === "object" && !Array.isArray(stored) ? stored : {};
  } catch {
    return {};
  }
}

const savedViews = storedViews();

export function savedView(key: string): SavedView | undefined {
  return savedViews[key];
}

/** Remembers where a sheet was left; only the most recent 200 sheets are kept. */
export function saveView(key: string, view: SavedView): void {
  delete savedViews[key];
  savedViews[key] = { x: Math.round(view.x), y: Math.round(view.y), zoom: Math.round(view.zoom * 1000) / 1000 };
  const keys = Object.keys(savedViews);
  for (const old of keys.slice(0, Math.max(0, keys.length - 200))) delete savedViews[old];
  try {
    localStorage.setItem("reticulum.views", JSON.stringify(savedViews));
  } catch {
    // Private windows and blocked storage: views are remembered until the page closes.
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
  layers: storedLayers(),
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
   * A blueprint instance open on the canvas: the sheet its node is on, and that node. The canvas shows its blueprint
   * ({@code sheetId}), read-only but for filling its slots. An instance inside a blueprint, opened from within an instance of
   * that blueprint, keeps the instances it was opened through ({@code outer}, from the one placed on a sheet down): it
   * runs once per instance of the blueprint holding it, and they say which run is shown.
   */
  instance: null as { sheet: string; node: string; outer?: { sheet: string; node: string }[] } | null,
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
  /** A node just added to be named: its panel's name field takes the keyboard, its default name selected. */
  nameNode: null as string | null,
  /** A panel shown in place of the inspector ("network" = network settings). */
  panel: null as "network" | "identified" | "dashboard" | "charts" | null,
  /** Switching to a network not seen yet this session: the one left stays shown (out of reach) until its snapshot comes. */
  switching: false,
  /** How long the network's ticks take, in ms (last 200 ticks). */
  tick: null as { average: number; longest: number } | null,
  busy: 0,
  toasts: [] as Toast[],
  undo: [] as Change[],
  redo: [] as Change[],
});

let toastSeq = 0;

export function toast(text: string, kind: Toast["kind"] = "error", action?: Toast["action"]): void {
  const id = ++toastSeq;
  app.toasts.push({ id, text, kind, action });
  // One with a button stays long enough to use it.
  setTimeout(() => {
    app.toasts = app.toasts.filter((t) => t.id !== id);
  }, kind === "error" || action ? 7000 : 3000);
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
      // A reconnect keeps the network this page shows; a new page opens on the one the game focused.
      // Subscribed again either way: the game's relay let go of this page's networks when its socket closed.
      if (m.connected) await loadNetworks(app.current ?? m.focus ?? hashNetwork(), true);
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
      // The one already shown: nothing to do (a fresh select would clear the selection for nothing).
      else if (m.network && (m.network !== app.current || !app.snapshot)) await selectNetwork(m.network);
      break;
    case "snapshot":
      if (m.network.id === app.current) setSnapshot(m.network);
      break;
    case "networks":
      app.networks = m.networks;
      forgetUnlisted();
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

/**
 * Signal values just set from here (a dashboard switch), by key: shown over what the game reports until it reports
 * them too, or until {@code until} (ms) — a report from before the game applied one would otherwise put it back.
 */
export const pendingSignals = new Map<string, { value: number; until: number }>();

function withPending(signals: Record<string, number>): Record<string, number> {
  if (!pendingSignals.size) return signals;
  const now = Date.now();
  const out = { ...signals };
  for (const [key, p] of pendingSignals) {
    if ((signals[key] ?? 0) === p.value || now > p.until) pendingSignals.delete(key);
    else out[key] = p.value;
  }
  return out;
}

function applyMetrics(m: any) {
  const next = {
    rates: m.perSecond ?? {},
    fiveMinutes: m.fiveMinutes ?? {},
    ticksSinceMoved: m.ticksSinceMoved ?? {},
    status: m.status ?? {},
    signals: withPending(m.signals ?? {}),
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
    forgetUnlisted();
    // The one asked for, else the one shown if it is still there (not after leaving its world), else the first.
    const listed = (id: string | null) => !!id && app.networks.some((n) => n.id === id);
    const target = listed(focus) ? focus : listed(app.current) ? app.current : (app.networks[0]?.id ?? null);
    if (target && (target !== app.current || resubscribe)) await selectNetwork(target);
  } catch (e) {
    toast((e as Error).message);
  }
}

/** Each network's undo and redo while another is shown: switching back brings them back. */
const histories = new Map<string, { undo: Change[]; redo: Change[] }>();

/** A network's undo and redo: the shown one's, or the history kept for another (a change finishing after a switch). */
function stacksOf(network: string): { undo: Change[]; redo: Change[] } {
  return network === historyOwner ? app : historyOf(network);
}

/**
 * The network whose undo and redo app.undo and app.redo hold. Kept apart from app.current, which is briefly null when
 * the shown network goes away: its history is still filed under it, and the next network's brought in.
 */
let historyOwner: string | null = null;

/** Forgets what is kept for networks no longer listed (deleted, access lost, another world). */
function forgetUnlisted(): void {
  const listed = new Set(app.networks.map((n) => n.id));
  for (const id of [...lastSnapshots.keys()]) if (!listed.has(id)) lastSnapshots.delete(id);
  for (const id of [...histories.keys()]) if (!listed.has(id)) histories.delete(id);
}

/**
 * The last change on this stack that belongs to the network shown; any on top from another network (left there when
 * the shown one went away, say) are filed with their own network's history instead of being sent.
 */
function takeOwn(network: string, kind: "undo" | "redo"): Change | undefined {
  const stack = stacksOf(network)[kind];
  for (let change = stack.pop(); change; change = stack.pop()) {
    if (change.network === network) return change;
    historyOf(change.network)[kind].push(change);
  }
  return undefined;
}

function historyOf(network: string): { undo: Change[]; redo: Change[] } {
  let stacks = histories.get(network);
  if (!stacks) histories.set(network, (stacks = { undo: [], redo: [] }));
  return stacks;
}

/**
 * Each network's last snapshot this session: switching back shows it at once while the game sends a fresh one, rather
 * than the whole editor giving way to "Loading…" for a moment (a flash).
 */
const lastSnapshots = new Map<string, Snapshot>();

/**
 * Shows another network, with its own undo history. Until its snapshot comes, what is shown (the network left, or this
 * one as last seen) is out of reach, so no edit goes out built on it. One that can't be opened goes back to the last.
 */
export async function selectNetwork(id: string, fallback = true): Promise<void> {
  const previous = app.current;
  if (historyOwner !== id) {
    // Kept for the network left, unless it is gone (removed, another world): then there's nothing to come back to.
    if (historyOwner && app.networks.some((n) => n.id === historyOwner)) histories.set(historyOwner, { undo: app.undo, redo: app.redo });
    const stacks = historyOf(id);
    app.undo = stacks.undo;
    app.redo = stacks.redo;
    histories.delete(id);
    historyOwner = id;
  }
  if (previous && previous !== id) {
    connection.send({ op: "unsubscribe", network: previous }).catch(() => {});
    // Not cleared to nothing: the editor would give way to "Loading…" for a moment and be built again (a flash).
    // The network left stays shown, out of reach, until this one's snapshot comes.
    app.switching = !lastSnapshots.has(id) && !!app.snapshot;
    if (!app.switching) app.snapshot = null;
  }
  app.current = id;
  const last = previous !== id ? lastSnapshots.get(id) : undefined;
  if (last) {
    setSnapshot(last);
    // As last seen, maybe long ago (others may have edited it since): shown, but out of reach until the fresh one.
    app.switching = true;
  }
  history.replaceState(null, "", `#network=${id}`);
  clearMetrics();
  app.power = null;
  app.tick = null;
  app.selectedNodes = [];
  app.selectedEdges = [];
  app.selectedDevice = null;
  try {
    const r = await connection.send({ op: "subscribe", network: id });
    if (app.current === id) setSnapshot(r.network);
  } catch (e) {
    toast((e as Error).message);
    lastSnapshots.delete(id);
    if (app.current !== id) return;
    // Nothing of it to show (what is shown is the network left, or a stale one): not left up to be edited.
    app.switching = false;
    app.snapshot = null;
    // One step back only (the network left, else another listed): never from a fallback, so failures can't bounce
    // between networks forever (every request fails at once while the game is unreachable).
    if (!fallback) return;
    const back = previous && previous !== id ? previous : app.networks.find((n) => n.id !== id)?.id;
    if (back) await selectNetwork(back, false);
  }
}

/** No live activity shown (another network, or this one stopped). */
function clearMetrics(): void {
  if (Object.keys(app.rates).length) app.rates = {};
  if (Object.keys(app.fiveMinutes).length) app.fiveMinutes = {};
  if (Object.keys(app.ticksSinceMoved).length) app.ticksSinceMoved = {};
  if (Object.keys(app.status).length) app.status = {};
  if (Object.keys(app.signals).length) app.signals = {};
  if (app.sheetsOff.length) app.sheetsOff = [];
  if (Object.keys(app.me).length) app.me = {};
}

function setSnapshot(snapshot: Snapshot): void {
  if (snapshot.id !== app.current) return;
  // Unchanged parts keep their objects, so after an edit only what it changed re-renders.
  // What the game took from here shows in it now.
  confirmedNodes.clear();
  const merged = app.snapshot && app.snapshot.id === snapshot.id ? share(app.snapshot, snapshot) : snapshot;
  if (merged !== app.snapshot) app.snapshot = merged;
  lastSnapshots.set(snapshot.id, merged);
  app.switching = false;
  const summary = app.networks.find((n) => n.id === snapshot.id);
  if (summary) Object.assign(summary, { name: snapshot.name, running: snapshot.running, enabled: snapshot.enabled });
  // Falling back to another sheet (the one open is gone, or this is another network) keeps the top bar's tab open:
  // the dashboard, charts, identified or settings follow to the network shown.
  const panel = app.panel;
  if (app.instance) {
    // An open instance that is gone (deleted, turned into a sheet, its blueprint deleted): back to the sheet holding it.
    // One now using another blueprint: that one opens.
    const open = openInstanceNode();
    // Opened through outer placed blueprints, one of which is gone: back to where the way in started.
    const wayIn = app.instance.outer?.length && open && !open.run ? app.instance.outer[0].sheet : null;
    if (wayIn) openSheet(snapshot.sheets.some((s) => s.id === wayIn) ? wayIn : (rootSheet(snapshot)?.id ?? ""));
    else if (!open) openSheet(snapshot.sheets.some((s) => s.id === app.instance!.sheet) ? app.instance.sheet : (rootSheet(snapshot)?.id ?? ""));
    else if (open.blueprint.id !== app.sheetId) openInstance(open.sheet.id, open.node.id, app.instance.outer);
  } else if (!snapshot.sheets.some((s) => s.id === app.sheetId)) openSheet(rootSheet(snapshot)?.id ?? snapshot.sheets[0]?.id ?? "");
  app.panel = panel;
  // A network that stopped gets no more metrics: what it last moved isn't shown as moving.
  if (!snapshot.running) {
    clearMetrics();
    app.power = null;
    app.tick = null;
  }
  // No live values (stopped, or none yet since opening it): switches and the like show what the game remembers.
  if (!snapshot.running || !Object.keys(app.signals).length) app.signals = withPending({ ...(snapshot.memory ?? {}) });
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

/** Node versions the game took that the snapshot may not show yet (cleared by the next one). */
const confirmedNodes = new Map<string, GraphNode>();

/**
 * How a node edit was made: {@code mine} from {@code base}. When its turn to be sent comes, the edits before it have been
 * answered, so it is made again on what the game really has: one refused is left out, and its undo restores that.
 */
const plans = new WeakMap<GraphNode, { base: GraphNode; mine: GraphNode; before: GraphNode }>();

/** The node as the game has it now, as far as is known: what it last took from here, else the snapshot. */
function confirmedNode(id: string): GraphNode | undefined {
  const known = confirmedNodes.get(id);
  if (known) return known;
  for (const sheet of app.snapshot?.sheets ?? []) {
    const node = sheet.nodes?.find((n) => n.id === id);
    if (node) return node;
  }
  return undefined;
}

/** {@code edits} (and their undo) with each planned node edit made again on what the game has by now. */
function replanned(edits: Edit[], undo?: Edit[]): { edits: Edit[]; undo?: Edit[] } {
  const restore = new Map<GraphNode, GraphNode>();
  const out = edits.map((e) => {
    const plan = e.type === "updateNode" && e.node ? plans.get(e.node as GraphNode) : undefined;
    if (!plan) return e;
    const theirs = confirmedNode(plan.mine.id);
    if (!theirs) return e;
    restore.set(plan.before, theirs);
    return { ...e, node: same(theirs, plan.base) ? plan.mine : (merged(plan.base, plan.mine, theirs) as GraphNode) };
  });
  return { edits: out, undo: undo?.map((e) => (e.type === "updateNode" && e.node && restore.has(e.node as GraphNode) ? { ...e, node: restore.get(e.node as GraphNode) } : e)) };
}

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
    let sending: { edits: Edit[]; undo?: Edit[] } = { edits, undo };
    try {
      // Made again on what the game has now: the edits before this one are answered.
      sending = replanned(edits, undo);
      const r = await connection.send({ op: "edit", network, edits: sending.edits });
      for (const e of sending.edits) {
        if ((e.type === "updateNode" || e.type === "addNode") && e.node) confirmedNodes.set((e.node as GraphNode).id, e.node as GraphNode);
      }
      undo = sending.undo;
      edits = sending.edits;
      // Made on another network than the one now shown (a confirm answered after switching): its own history. Any
      // change (one that can't be undone too) leaves nothing to redo: what redo would bring back may be gone.
      const stacks = stacksOf(network);
      if (undo) {
        stacks.undo.push({ network, label, redo: edits, undo });
        if (stacks.undo.length > HISTORY) stacks.undo.shift();
      }
      stacks.redo = [];
      return r.results as unknown[];
    } catch (e) {
      if (lostAnswer(e)) {
        // The game may have done it: undoable all the same (refused when undone, it wasn't), and redo can't follow it.
        const stacks = stacksOf(network);
        if (sending.undo) pushCapped(stacks.undo, { network, label, redo: sending.edits, undo: sending.undo, uncertain: true });
        stacks.redo = [];
        toast(`No answer about “${label}” (${(e as Error).message}) — it may or may not have been done.`);
      } else {
        toast((e as Error).message);
      }
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
 * last sent for that node if its answer hasn't come back yet. Only what this edit changed is taken from {@code next},
 * however deep (a cap's amount inside an input), so typing an amount and then ticking a face keeps both. Returns the
 * node to send, and the one to restore on undo.
 */
export function rebase(node: GraphNode, next: GraphNode): { after: GraphNode; before: GraphNode } {
  const before = sentNodes.get(node.id) ?? node;
  const after = before === node ? next : (merged(node, next, before) as GraphNode);
  plans.set(after, { base: node, mine: next, before });
  return { after, before };
}

/**
 * {@code mine} and {@code theirs}, both made from {@code base}: what only one changed is taken from it; where both
 * changed the same thing, mine wins. Objects merge key by key, lists of the same length item by item, and a list one
 * side resized keeps the other side's changes to the items it didn't touch.
 */
function merged(base: unknown, mine: unknown, theirs: unknown): unknown {
  if (same(mine, base)) return theirs;
  if (same(theirs, base)) return mine;
  if (Array.isArray(base) && Array.isArray(mine) && Array.isArray(theirs)) {
    if (base.length === mine.length && base.length === theirs.length) return mine.map((item, i) => merged(base[i], item, theirs[i]));
    // One side added or removed items, the other only changed some: the items the first left as they were are
    // found in the original list, and take the other side's changes to them.
    if (base.length === theirs.length) return realign(base, mine, (k, item) => (k < 0 ? item : theirs[k]));
    if (base.length === mine.length) return realign(base, theirs, (k, item) => (k < 0 ? item : merged(base[k], mine[k], item)));
    return resizedBoth(base, mine, theirs);
  }
  if (isRecord(base) && isRecord(mine) && isRecord(theirs)) {
    const out: Record<string, unknown> = {};
    for (const key of new Set([...Object.keys(base), ...Object.keys(mine), ...Object.keys(theirs)])) {
      const value = merged(base[key], mine[key], theirs[key]);
      if (value !== undefined) out[key] = value;
    }
    return out;
  }
  return mine;
}

/**
 * Both sides added or removed items (two quick "add input"s): theirs, less the items mine removed, with the items mine
 * added put after the item they followed in mine (and after those theirs added there, which came first).
 */
function resizedBoth(base: unknown[], mine: unknown[], theirs: unknown[]): unknown[] {
  const match = (list: unknown[]) => {
    const used = new Set<number>();
    return list.map((item) => {
      const k = base.findIndex((b, j) => !used.has(j) && same(b, item));
      if (k >= 0) used.add(k);
      return k;
    });
  };
  const inMine = match(mine);
  const kept = new Set(inMine.filter((k) => k >= 0));
  const out: { item: unknown; k: number }[] = match(theirs)
    .map((k, i) => ({ item: theirs[i], k }))
    .filter((e) => e.k < 0 || kept.has(e.k));
  let at = -1;
  mine.forEach((item, i) => {
    const k = inMine[i];
    if (k >= 0) {
      const found = out.findIndex((e) => e.k === k);
      if (found >= 0) at = found;
      return;
    }
    let to = at + 1;
    while (to < out.length && out[to].k < 0) to++;
    out.splice(to, 0, { item, k: -2 });
    at = to;
  });
  return out.map((e) => e.item);
}

/** Each item of {@code changed} with the index of an equal, not yet matched, item of {@code base} (-1: none), mapped. */
function realign(base: unknown[], changed: unknown[], map: (k: number, item: unknown) => unknown): unknown[] {
  const used = new Set<number>();
  return changed.map((item) => {
    const k = base.findIndex((b, j) => !used.has(j) && same(b, item));
    if (k >= 0) used.add(k);
    return map(k, item);
  });
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function same(a: unknown, b: unknown): boolean {
  return a === b || JSON.stringify(a) === JSON.stringify(b);
}

/** Steps of undo (and of redo) kept per network. */
const HISTORY = 100;

/** Adds a change to a stack, dropping the oldest past the limit. */
function pushCapped(stack: Change[], change: Change): void {
  stack.push(change);
  if (stack.length > HISTORY) stack.shift();
}

/** The game may or may not have done it: the connection dropped, or no answer came in time (not a refusal). */
function lostAnswer(e: unknown): boolean {
  const message = (e as Error)?.message;
  return message === "Connection to the game lost" || message === "The game didn't answer in time";
}

/** It never reached a network: not connected, or the game not in a world (out to the title screen, loading). */
function notSent(e: unknown): boolean {
  const message = (e as Error)?.message;
  return message === "Not connected to the game" || message === "Not in a world";
}

/**
 * Undo and redo skip a change the server refuses (said in a toast): nothing of it was done, and left on top it would
 * block every step below it. One not sent, or with no answer, is kept to try again; one with no answer that is refused
 * when tried again was done the first time, and goes on as done. {@code only}: undo that change only if it is still the
 * last one when its turn comes (edits sent before it may land first).
 */

export function undo(only?: (typeof app.undo)[number]): Promise<void> {
  // The network shown when asked: its turn may come after a switch to another.
  const network = app.current;
  if (!network) return Promise.resolve();
  return serially(async () => {
    const stack = stacksOf(network).undo;
    if (only && stack[stack.length - 1] !== only) {
      toast("Something else has changed since — use Undo in the top bar.", "info");
      return;
    }
    const change = takeOwn(network, "undo");
    if (!change) return;
    app.busy++;
    try {
      await connection.send({ op: "edit", network: change.network, edits: change.undo });
      pushCapped(stacksOf(change.network).redo, Object.assign(change, { uncertain: false }));
    } catch (e) {
      if (notSent(e)) {
        pushCapped(stacksOf(change.network).undo, change);
        toast(`Couldn't undo “${change.label}”: ${(e as Error).message} — it's kept: Undo again once the game is back.`);
      } else if (lostAnswer(e)) {
        // Maybe done, maybe not: kept, so it isn't lost. Done twice, the game refuses or changes nothing.
        pushCapped(stacksOf(change.network).undo, Object.assign(change, { uncertain: true }));
        toast(`No answer about undoing “${change.label}” (${(e as Error).message}) — it's kept: Undo again once the game is back.`);
      } else if (change.uncertain) {
        // Tried before with no answer, refused now: that first try was done.
        pushCapped(stacksOf(change.network).redo, Object.assign(change, { uncertain: false }));
        toast(`“${change.label}” was undone already (the game says: ${(e as Error).message}).`, "info");
      } else {
        // Nothing of it was done (a batch is all or nothing); left on top it would block every step below it.
        toast(`Couldn't undo “${change.label}”: ${(e as Error).message}. Skipped — Undo goes on from the step before.`);
      }
      resync.count++;
    } finally {
      app.busy--;
    }
  });
}

export function redo(): Promise<void> {
  const network = app.current;
  if (!network) return Promise.resolve();
  return serially(async () => {
    const change = takeOwn(network, "redo");
    if (!change) return;
    app.busy++;
    try {
      await connection.send({ op: "edit", network: change.network, edits: change.redo });
      pushCapped(stacksOf(change.network).undo, Object.assign(change, { uncertain: false }));
    } catch (e) {
      if (notSent(e)) {
        pushCapped(stacksOf(change.network).redo, change);
        toast(`Couldn't redo “${change.label}”: ${(e as Error).message} — it's kept: Redo again once the game is back.`);
      } else if (lostAnswer(e)) {
        pushCapped(stacksOf(change.network).redo, Object.assign(change, { uncertain: true }));
        toast(`No answer about redoing “${change.label}” (${(e as Error).message}) — it's kept: Redo again once the game is back.`);
      } else if (change.uncertain) {
        pushCapped(stacksOf(change.network).undo, Object.assign(change, { uncertain: false }));
        toast(`“${change.label}” was redone already (the game says: ${(e as Error).message}).`, "info");
      } else {
        toast(`Couldn't redo “${change.label}”: ${(e as Error).message}. Skipped.`);
      }
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

/** Whether a node is on a blueprint (a device node there is a slot). */
export function inBlueprint(nodeId: string): boolean {
  return !!app.snapshot?.sheets.some((s) => s.blueprint && s.nodes?.some((n) => n.id === nodeId));
}

/**
 * What a node is called everywhere (its card, the inspector, the dashboard, wires): its label, else its device's
 * name, else the blueprint it uses or its pin name, else its kind.
 */
export function nodeTitle(node: GraphNode | undefined): string {
  if (!node) return "?";
  // A sheet node goes by its sheet's name and a device node by its device's (a slot, in a blueprint, by its own).
  const own = node.settings.kind !== "sheet" && !(node.settings.kind === "device" && !inBlueprint(node.id));
  if (node.label && own) return node.label;
  const s = node.settings;
  if (s.kind === "device") return deviceName(deviceAt(posKey(s.device)));
  if (s.kind === "instance") return app.snapshot?.sheets.find((x) => x.id === s.sheet)?.name ?? "Missing blueprint";
  if (s.kind === "sheet") return app.snapshot?.sheets.find((x) => x.id === s.sheet)?.name ?? "Missing sheet";
  if ((s.kind === "sheet_input" || s.kind === "sheet_output") && s.name) return s.name;
  return KIND_LABEL[s.kind];
}

/**
 * One place a device is used: a sheet (which opens it, the node selected).
 * {@code home}: its device node, where it lives (one per device; blueprints' instances aside).
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
      // Its node (its home), or on a blueprint a slot made from it; else a node reading it (a Level sensor).
      if (s.kind === "device" && !sheet.blueprint) add(s.device, { label, sheetId: sheet.id, nodeId: node.id, home: true });
      else if (s.kind === "device") add(s.device, { label: `${label}: a blueprint slot is made from it (where its layout measures from)`, sheetId: sheet.id, nodeId: node.id });
      // A sensor reading a blueprint slot reads whatever fills it, not the block the slot was laid out on.
      else if ("device" in s && !(s.kind === "level" && s.slot)) add(s.device as GlobalPos, { label: `${label}: a ${KIND_LABEL[s.kind]} reads it`, sheetId: sheet.id, nodeId: node.id });
      // A block filling a slot of an instance lives in that instance.
      if (s.kind === "instance" && !sheet.blueprint) {
        const instanceLabel = `${label} › ${nodeTitle(node)}`;
        const blueprint = snapshot.sheets.find((b) => b.id === s.sheet);
        // Only slots the blueprint still has (those of blueprints placed in it too): one taken out leaves its block free
        // (as the server sees it).
        const keys = new Set(allSlotKeys(blueprint, snapshot.sheets));
        for (const [slot, binding] of Object.entries(s.bindings ?? {})) {
          if (keys.has(slot)) add(binding.device, { label: instanceLabel, sheetId: sheet.id, nodeId: node.id, home: true });
        }
      }
    }
  }
  return uses;
}

/** Switches a sheet on or off (its sheet node's switch; the network's, for the root), undoably. */
export function setSheetEnabled(sheet: Sheet, on: boolean) {
  return edit([{ type: "updateSheet", sheet: sheet.id, enabled: on }], [{ type: "updateSheet", sheet: sheet.id, enabled: sheet.enabled !== false }], "sheet on/off");
}

/** Where a new node goes in a sheet so it covers nothing: right of what is there, level with the top one. */
export function freeSpot(sheet: Sheet | null | undefined): { x: number; y: number } {
  const nodes = sheet?.nodes ?? [];
  return {
    x: nodes.length ? Math.max(...nodes.map((n) => n.x ?? 0)) + 260 : 40,
    y: nodes.length ? Math.min(...nodes.map((n) => n.y ?? 0)) : 40,
  };
}

/** "Sheet 3" (or "Blueprint 3"): the first number not taken by one of that name. */
export function nextName(prefix: string): string {
  const taken = new Set((app.snapshot?.sheets ?? []).map((s) => s.name));
  let n = 1;
  while (taken.has(`${prefix} ${n}`)) n++;
  return `${prefix} ${n}`;
}

/**
 * Makes a new sheet inside {@code parent} (a sheet node there, at {@code at}), opening it unless told not to.
 * Returns its id.
 */
export async function createSheet(parent: Sheet | null, name: string, at?: { x: number; y: number }, open = true): Promise<string | null> {
  const network = app.current;
  const results = await edit([
    { type: "createSheet", name, ...(parent ? { parent: parent.id } : {}), ...(at ? { x: Math.round(at.x), y: Math.round(at.y) } : {}) },
  ]);
  const id = (results?.[0] as string | undefined) ?? null;
  // Not opened if another network is shown by now (its id means nothing there).
  if (id && open && app.current === network) openSheet(id);
  return id;
}

/**
 * Adds a pin to a sheet's edge (an input on the left, an output on the right), below the pins already there, to be
 * named next. Added on the canvas it is selected (its own panel); added from the sheet's panel ({@code select}
 * false) it is named there, in its group. Returns its id.
 */
export async function addSheetPin(sheet: Sheet, input: boolean, select = true): Promise<string | null> {
  const kind = input ? "sheet_input" : "sheet_output";
  const same = (sheet.nodes ?? []).filter((n) => n.settings.kind === kind);
  const y = same.length ? Math.max(...same.map((n) => n.y ?? 0)) + 40 : Math.min(0, ...(sheet.nodes ?? []).map((n) => n.y ?? 0)) + 30;
  const node: GraphNode = { id: uuid(), settings: { kind, name: "" }, x: 0, y: Math.round(y) };
  const ok = await edit([{ type: "addNode", sheet: sheet.id, node }], [{ type: "removeNode", sheet: sheet.id, node: node.id }], input ? "add input pin" : "add output pin");
  if (!ok) return null;
  app.nameNode = node.id;
  if (select) {
    app.selectedNodes = [node.id];
    app.selectedEdges = [];
  }
  return node.id;
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
  // Its node at a free spot in the new parent (its old place there was in the old parent's space, maybe on another node).
  const at = freeSpot(parent);
  const back = sheet.node ? from?.nodes?.find((n) => n.id === sheet.node) : undefined;
  if (back && undo.length) undo[0] = { ...undo[0], x: back.x ?? 0, y: back.y ?? 0 };
  return edit([{ type: "moveSheet", sheet: sheet.id, parent: parent.id, x: at.x, y: at.y }], from ? undo : undefined, "move sheet");
}

/** Shows a sheet, with nothing selected (or the given nodes), and the inspector for it. */
export function openSheet(id: string, select: string[] = [], focus = false): void {
  app.sheetId = id;
  app.instance = null;
  app.focusNode = focus && select.length ? select[0] : null;
  app.selectedNodes = select;
  app.selectedEdges = [];
  app.selectedDevice = null;
  app.panel = null;
}

/** Opens a blueprint instance: its blueprint on the canvas, with the blocks filling its slots ({@code outer}: see app.instance). */
export function openInstance(sheetId: string, node: string, outer: { sheet: string; node: string }[] = []): void {
  const held = app.snapshot?.sheets.find((s) => s.id === sheetId)?.nodes?.find((n) => n.id === node);
  if (held?.settings.kind !== "instance") return;
  openSheet(held.settings.sheet);
  app.instance = { sheet: sheetId, node, ...(outer.length ? { outer } : {}) };
}

const openRun = $derived(app.instance ? (openInstanceNode()?.run ?? null) : null);

/** A view over the whole canvas (Charts, the dashboard): the canvas and inspector are out of sight, and out of reach of keys. */
export function overCanvas(): boolean {
  return app.panel === "charts" || app.panel === "dashboard";
}

/** The id the open instance runs under (null when none is open, or it doesn't run itself): see openInstanceNode. */
export function instanceRun(): string | null {
  return openRun;
}

/**
 * Opens the instance node {@code node} on {@code on}: from within the instance open now when {@code on} is its blueprint (so
 * the instance inside is shown as it runs in that one), else on its own.
 */
export function openInstanceFrom(on: Sheet, node: string): void {
  const open = app.instance && on.blueprint ? openInstanceNode() : null;
  const outer = open && open.blueprint.id === on.id ? [...(app.instance!.outer ?? []), { sheet: app.instance!.sheet, node: app.instance!.node }] : [];
  openInstance(on.id, node, outer);
}

/**
 * The instance open on the canvas: the sheet it is on, its node and its blueprint (null when none, or it is gone), and
 * {@code run}: the id it runs under, which its nodes' activity is kept by. Null when it doesn't run itself: an instance
 * inside a blueprint opened on its own (it runs once per instance of that blueprint), or one whose way in is gone.
 */
export function openInstanceNode(): { sheet: Sheet; node: GraphNode & { settings: InstanceSettings }; blueprint: Sheet; run: string | null } | null {
  const at = app.instance;
  const sheets = app.snapshot?.sheets ?? [];
  const sheet = at && sheets.find((s) => s.id === at.sheet);
  const node = sheet?.nodes?.find((n) => n.id === at!.node);
  if (!sheet || node?.settings.kind !== "instance") return null;
  const blueprint = sheets.find((s) => s.id === (node.settings as InstanceSettings).sheet);
  if (!blueprint) return null;
  // The way in, outside in: each instance must sit on the blueprint of the one before it, the first on a sheet that runs.
  const chain = [...(at!.outer ?? []), { sheet: at!.sheet, node: at!.node }];
  let run: string | null = null;
  let expected: string | undefined;
  for (const [i, step] of chain.entries()) {
    const on = sheets.find((s) => s.id === step.sheet);
    const instance = on?.nodes?.find((n) => n.id === step.node);
    if (!on || instance?.settings.kind !== "instance" || (i === 0 ? on.blueprint : on.id !== expected)) {
      run = null;
      break;
    }
    run = i === 0 ? instance.id : inInstance(run!, instance.id);
    expected = instance.settings.sheet;
  }
  return { sheet, node: node as GraphNode & { settings: InstanceSettings }, blueprint, run };
}

/**
 * Where the open instance's slots are filled: the placement it runs in (the first of the way in, on a sheet that runs)
 * and the key each of the open blueprint's slots has in that placement's bindings. A blueprint placed inside another
 * holds no blocks itself; each placement of the outer one fills it. Null when the open instance doesn't run (one inside a
 * blueprint opened on its own): nothing is filled there.
 */
export interface BindingScope {
  holder: Sheet;
  node: GraphNode & { settings: InstanceSettings };
  key: (slot: string) => string;
}

export function bindingScope(): BindingScope | null {
  const at = app.instance;
  if (!at || !openInstanceNode()?.run) return null;
  const chain = [...(at.outer ?? []), { sheet: at.sheet, node: at.node }];
  const holder = app.snapshot?.sheets.find((s) => s.id === chain[0].sheet);
  const node = holder?.nodes?.find((n) => n.id === chain[0].node);
  if (!holder || node?.settings.kind !== "instance") return null;
  const path = chain.slice(1).map((c) => c.node);
  return { holder, node: node as GraphNode & { settings: InstanceSettings }, key: (slot) => path.reduceRight((k, placed) => inInstance(placed, k), slot) };
}

/** The open blueprint's slots as {@code scope}'s placement fills them, by slot id (all of them: theirs and those placed in it). */
export function scopedBindings(scope: BindingScope | null, blueprint: Sheet): Record<string, Binding> {
  if (!scope) return {};
  const all = scope.node.settings.bindings ?? {};
  const out: Record<string, Binding> = {};
  for (const key of allSlotKeys(blueprint, app.snapshot?.sheets ?? [])) {
    const b = all[scope.key(key)];
    if (b) out[key] = b;
  }
  return out;
}

/** {@link setBindings} for the open instance's slots, written into the placement it runs in. */
export function setScopedBindings(scope: BindingScope, blueprint: Sheet, change: (bindings: Record<string, Binding>) => Record<string, Binding>, label = "fill slot") {
  const keys = allSlotKeys(blueprint, app.snapshot?.sheets ?? []);
  return setBindings(
    scope.holder,
    scope.node,
    (all) => {
      const view: Record<string, Binding> = {};
      for (const key of keys) if (all[scope.key(key)]) view[key] = all[scope.key(key)];
      const next = change(view);
      const out = { ...all };
      for (const key of keys) delete out[scope.key(key)];
      for (const [key, b] of Object.entries(next)) out[scope.key(key)] = b;
      return out;
    },
    label,
  );
}

/**
 * Changes which blocks fill an instance's slots, undoably. {@code change} gets the fillings as last sent (an earlier fill
 * may not be back from the server yet) and returns the new ones.
 */
export function setBindings(
  sheet: Sheet,
  node: GraphNode & { settings: InstanceSettings },
  change: (bindings: Record<string, Binding>) => Record<string, Binding>,
  label = "fill slot",
) {
  const before = (sentNodes.get(node.id) ?? node) as GraphNode & { settings: InstanceSettings };
  const next = { ...before, settings: { ...before.settings, bindings: change(before.settings.bindings ?? {}) } };
  plans.set(next, { base: before, mine: next, before });
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

/** Deletes a sheet, after asking: its nodes and the sheets inside it go with it. It can't be undone, as on the canvas. */
export async function deleteSheet(sheet: Sheet) {
  const nodes = sheet.nodes?.length ?? 0;
  const inside = descendants(app.snapshot!, sheet).length;
  const parts = [
    nodes ? `its ${nodes} node${nodes === 1 ? "" : "s"} and their wires` : "",
    inside ? `the ${inside} sheet${inside === 1 ? "" : "s"} inside it` : "",
  ].filter(Boolean);
  const what = sheet.blueprint ? "blueprint" : "sheet";
  // A blueprint's instances keep their nodes but have nothing left to run.
  const instances = sheet.blueprint ? instancesOf(app.snapshot!, sheet.id).length : 0;
  const message = [
    parts.length ? `${parts.join(" and ")[0].toUpperCase()}${parts.join(" and ").slice(1)} go with it.` : "",
    instances === 1 ? "Where it's placed, it stops working and shows an error until removed." : "",
    instances > 1 ? `In the ${instances} places it's placed, it stops working and shows an error until removed.` : "",
    "This can't be undone.",
  ].filter(Boolean).join(" ");
  const network = app.current;
  const ok = await confirmDialog({
    title: `Delete the ${what} “${sheet.name}”?`,
    message,
    confirm: `Delete ${what}`,
    danger: true,
  });
  // To the network it was asked on, even if another is shown by the time it is answered.
  if (ok && network) {
    edit([{ type: "deleteSheet", sheet: sheet.id }], undefined, "delete", network);
  }
}

/** Deletes a blueprint instance (its node and wires, one undoable change); an open instance goes back to the sheet that held it. */
export async function deleteInstance(holder: Sheet, node: GraphNode) {
  const wires = (holder.wires ?? []).filter((w) => w.from === node.id || w.to === node.id);
  const ok = await edit(
    [{ type: "removeNode", sheet: holder.id, node: node.id }],
    [{ type: "addNode", sheet: holder.id, node } as Edit, ...wires.map((wire) => ({ type: "connect", sheet: holder.id, wire }) as Edit)],
    "remove placed blueprint",
  );
  if (ok && app.instance?.node === node.id) openSheet(holder.id);
}

/** Copies a sheet or blueprint as JSON, to paste with the matching import here or in another world. */
export async function copySheetJson(sheet: Sheet) {
  const all = app.snapshot?.sheets ?? [];
  const plain = ({ errors: _errors, parent: _parent, node: _node, enabled: _enabled, root: _root, ...rest }: Sheet) => rest;
  // What it needs comes along: the sheets inside it, the blueprints placed in it (and in those), the templates they name.
  const inside: Sheet[] = [];
  const blueprints: Sheet[] = [];
  const todo = [sheet];
  while (todo.length) {
    const at = todo.pop()!;
    for (const n of at.nodes ?? []) {
      const s = n.settings as { kind?: string; sheet?: string };
      const used = all.find((x) => x.id === s.sheet);
      if (!used || used === sheet) continue;
      if (s.kind === "sheet" && !used.blueprint && !inside.includes(used)) inside.push(used), todo.push(used);
      else if (s.kind === "instance" && used.blueprint && !blueprints.includes(used)) blueprints.push(used), todo.push(used);
    }
  }
  const filters: Record<string, string> = {};
  const name = (v: unknown) => {
    if (typeof v === "string") {
      for (const r of references(v)) {
        const expression = app.snapshot?.filters?.[r];
        if (expression !== undefined && !(r in filters)) {
          filters[r] = expression;
          name(expression);
        }
      }
    } else if (Array.isArray(v)) v.forEach(name);
    else if (v && typeof v === "object") Object.values(v).forEach(name);
  };
  for (const s of [sheet, ...inside, ...blueprints]) for (const n of s.nodes ?? []) name(n.settings);
  const bundle = inside.length || blueprints.length || Object.keys(filters).length ? { bundle: { sheets: [...inside, ...blueprints].map(plain), filters } } : {};
  try {
    await navigator.clipboard.writeText(JSON.stringify({ ...plain(sheet), ...bundle }, null, 2));
    toast(`Copied. Paste it with the ${sheet.blueprint ? "Blueprints" : "Sheets"} import, here or in another world.`, "info");
  } catch {
    toast("Couldn't reach the clipboard.");
  }
}

/** A new blueprint like this one, opened (to change without touching this one's instances). */
export async function duplicateBlueprint(sheet: Sheet) {
  const network = app.current;
  const results = await edit([{ type: "duplicateBlueprint", sheet: sheet.id }]);
  if (results?.[0] && app.current === network) openSheet(results[0] as string);
}

/**
 * Saves a node changed to {@code next}, as one undoable change: rebased on what was last sent for it (an earlier edit
 * may not be back from the server yet), its undo the exact reverse.
 */
export function updateNodeEdit(sheetId: string, node: GraphNode, next: GraphNode, label: string) {
  const { after, before } = rebase(node, next);
  return edit([{ type: "updateNode", sheet: sheetId, node: after }], [{ type: "updateNode", sheet: sheetId, node: before }], label);
}

/** A sheet pin with these settings changed; a name it still keeps in an old label moves into its name. */
export function pinChanged(pin: GraphNode, patch: Record<string, unknown>): GraphNode {
  const s = pin.settings as GraphNode["settings"] & { name?: string };
  return { ...pin, label: undefined, settings: { ...s, name: s.name || pin.label || "", ...patch } as GraphNode["settings"] };
}

/**
 * An Undo for a toast about the change just made: it undoes that change only while it is still the last one (another
 * edit since, it says so rather than undo that one).
 */
export function undoThis(): () => void {
  const change = app.undo[app.undo.length - 1];
  return () => {
    if (change) undo(change);
  };
}

/**
 * Pastes a sheet or blueprint copied with "Copy as JSON" (from this or another world). A blueprint lands among the
 * blueprints wherever it is pasted; the Blueprints import takes only blueprints (a sheet's devices aren't slots).
 */
export async function importSheet(what: "sheet" | "blueprint", into?: Sheet | null) {
  const snapshot = app.snapshot;
  if (!snapshot) return;
  const network = snapshot.id;
  const text = await promptDialog({
    title: what === "blueprint" ? "Import blueprint" : "Import sheet",
    message: `Paste a ${what} copied with “Copy as JSON”, from this world or another.`,
    confirm: "Import",
    multiline: true,
  });
  if (!text?.trim()) return;
  let sheet: { blueprint?: boolean; name?: string; bundle?: { sheets?: unknown[]; filters?: Record<string, string> } } | null = null;
  try {
    sheet = JSON.parse(text);
  } catch {
    // not JSON: said below
  }
  if (!sheet || typeof sheet !== "object") {
    toast(`That isn't a copied ${what} (not JSON).`);
    return;
  }
  if (what === "blueprint" && !sheet.blueprint) {
    toast("That's a sheet, not a blueprint — import it with the Sheets import (or make a blueprint from it once it's in).");
    return;
  }
  // A name already taken here gets marked, so the import can be told from what it came from.
  if (typeof sheet.name === "string" && snapshot.sheets.some((s) => s.name === sheet!.name)) sheet = { ...sheet, name: `${sheet.name} (imported)` };
  // Into the network it was asked on (its names checked against it), whatever is shown by now.
  // A sheet goes into the one asked about (the open one), at a free spot there; a blueprint isn't placed anywhere.
  const place = what === "sheet" && into ? { parent: into.id, ...freeSpot(into) } : {};
  // What it brought along (the sheets inside it, blueprints, templates) goes with it.
  const { bundle, ...main } = sheet;
  const brought = { sheets: Array.isArray(bundle?.sheets) ? bundle.sheets : [], filters: bundle?.filters && typeof bundle.filters === "object" ? bundle.filters : {} };
  // What it refers to that it didn't bring and this network lacks: said after, so nothing goes quietly.
  const left = leftBehind(main, brought, snapshot);
  const results = await edit([{ type: "importSheet", sheet: main, ...brought, ...place }], undefined, "import", network);
  const result = results?.[0] as { sheet?: string; notes?: string[] } | undefined;
  if (!result?.sheet || app.current !== network) return;
  openSheet(result.sheet);
  if (left) toast(left, "info");
  for (const note of result.notes ?? []) toast(`${note}.`, "info");
  if (sheet.blueprint) {
    toast(
      what === "blueprint" ? "Imported. Wherever it's placed, blocks of the same kinds fill its slots." : "That's a blueprint — it's been added to Blueprints.",
      "info",
    );
  } else {
    toast("Imported. Devices from another world show as offline until you pick local ones for them.", "info");
  }
}

/**
 * What an imported sheet refers to that it didn't bring along and this network lacks: sheets inside it (dropped),
 * blueprints it places (dropped), filter templates its filters name. Null when nothing.
 */
function leftBehind(pasted: unknown, brought: { sheets: unknown[]; filters: Record<string, string> }, snapshot: Snapshot): string | null {
  const bundled = brought.sheets.filter((s): s is Sheet => !!s && typeof s === "object");
  const nodes = [pasted, ...bundled].flatMap((s) => (s as { nodes?: GraphNode[] }).nodes ?? []);
  const has = (id: string | undefined, blueprint: boolean) => [...bundled, ...(blueprint ? snapshot.sheets : [])].some((s) => s.id === id && !!s.blueprint === blueprint);
  const sheets = nodes.filter((n) => n.settings?.kind === "sheet" && !has((n.settings as { sheet?: string }).sheet, false)).length;
  const blueprints = nodes.filter((n) => n.settings?.kind === "instance" && !has((n.settings as { sheet?: string }).sheet, true)).length;
  const named = new Set<string>();
  const walk = (v: unknown) => {
    if (typeof v === "string") references(v).forEach((r) => named.add(r));
    else if (Array.isArray(v)) v.forEach(walk);
    else if (v && typeof v === "object") Object.values(v).forEach(walk);
  };
  nodes.forEach((n) => walk(n.settings));
  const templates = [...named].filter((t) => !(t in (snapshot.filters ?? {})) && !(t in brought.filters));
  const parts = [
    sheets && `${sheets} sheet${sheets === 1 ? "" : "s"} inside it (copy it again to bring them along)`,
    blueprints && `${blueprints} placed blueprint${blueprints === 1 ? "" : "s"} whose blueprint isn't here`,
    templates.length && `filter template${templates.length === 1 ? "" : "s"} ${templates.map((t) => `@${t}`).join(", ")}`,
  ].filter(Boolean);
  return parts.length ? `Not brought along: ${parts.join("; ")}.` : null;
}

const placeListeners = new Set<(device: Device) => void>();

/** Asks the open canvas to add a node for this device ("Add to the open sheet" in the device list's menu). */
export function placeDevice(device: Device): void {
  for (const listener of placeListeners) listener(device);
}

/** The canvas taking {@link placeDevice} requests while it is shown; returns how to stop. */
export function onPlaceDevice(listener: (device: Device) => void): () => void {
  placeListeners.add(listener);
  return () => placeListeners.delete(listener);
}

/**
 * Whether a device already has its home: a device node on a sheet of the tree, or a slot of an instance it fills. Worked
 * out once, for asking about many devices.
 */
export function homeCheck(): (device: Device) => boolean {
  const uses = app.snapshot ? deviceUses(app.snapshot) : new Map<string, DeviceUse[]>();
  return (device) => (uses.get(posKey(device.pos)) ?? []).some((u) => u.home);
}

/**
 * Fills a slot of an instance with a device, turned as the slot already was (else as the instance's other slots are). The
 * instance's first block fills the rest by layout too: one undo step, offered on the toast.
 */
export async function fillSlot(scope: BindingScope, blueprint: Sheet, slot: GraphNode, device: Device): Promise<boolean> {
  const homed = homeCheck();
  let more = 0;
  const ok = await setScopedBindings(
    scope,
    blueprint,
    (bindings) => {
      const turns = bindings[slot.id]?.turns ?? Object.values(bindings).find((b) => b.turns)?.turns;
      const result = fillOne(blueprint, bindings, slot.id, { device: device.pos, ...(turns ? { turns } : {}) }, app.snapshot!.devices, homed, app.snapshot!.sheets);
      more = result.more;
      return result.bindings;
    },
    `fill ${slotName(slot)}`,
  );
  if (ok && more) toast(`Filled ${more} more slot${more === 1 ? "" : "s"} by layout from this one.`, "info", { label: "Undo", run: undoThis() });
  return !!ok;
}
