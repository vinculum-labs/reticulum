// The editor's shared state. The server is the only authority: edits are sent, and the snapshot it pushes back
// replaces what is shown. Undo and redo send the inverse edits recorded with each change.

import { Connection } from "./protocol";
import type { Device, Edit, GraphNode, NetworkSummary, Sheet, Snapshot, MeUsage, Face, Identify, PowerState } from "./types";
import { posKey } from "./types";

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
  /** Published network signals, by name. */
  networkSignals: {} as Record<string, number>,
  /** Sheets their redstone condition currently switches off. */
  sheetsOff: [] as string[],
  /** What running the network costs and what the controller holds, updated once a second. */
  power: null as PowerState | null,
  /** Channel use of each ME port node. */
  me: {} as Record<string, MeUsage>,
  selectedNodes: [] as string[],
  selectedEdges: [] as string[],
  selectedDevice: null as string | null,
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
      await loadNetworks(app.current ?? hashNetwork());
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
        app.rates = m.perSecond;
        app.fiveMinutes = m.fiveMinutes ?? {};
        app.ticksSinceMoved = m.ticksSinceMoved ?? {};
        app.status = m.status ?? {};
        app.signals = m.signals ?? {};
        app.networkSignals = m.networkSignals ?? {};
        app.sheetsOff = m.sheetsOff ?? [];
        app.me = m.me ?? {};
        app.power = m.power ?? null;
        app.tick = m.tick ?? null;
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

function hashNetwork(): string | null {
  const match = location.hash.match(/network=([0-9a-f-]+)/);
  return match ? match[1] : null;
}

export async function loadNetworks(focus: string | null): Promise<void> {
  try {
    const r = await connection.send({ op: "networks" });
    app.networks = r.networks;
    app.player = r.player;
    const target = focus && app.networks.some((n) => n.id === focus) ? focus : app.current ?? app.networks[0]?.id ?? null;
    if (target && target !== app.current) await selectNetwork(target);
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
    setSnapshot(r.network);
  } catch (e) {
    toast((e as Error).message);
  }
}

function setSnapshot(snapshot: Snapshot): void {
  app.snapshot = snapshot;
  const summary = app.networks.find((n) => n.id === snapshot.id);
  if (summary) Object.assign(summary, { name: snapshot.name, running: snapshot.running, enabled: snapshot.enabled });
  if (!snapshot.sheets.some((s) => s.id === app.sheetId)) app.sheetId = snapshot.sheets[0]?.id ?? null;
}

// ---------------------------------------------------------------- edits

/** Sends edits; returns the server's per-edit results (created ids) or null on failure. */
export async function edit(edits: Edit[], undo?: Edit[], label = "change"): Promise<unknown[] | null> {
  const network = app.current;
  if (!network || edits.length === 0) return null;
  app.busy++;
  try {
    const r = await connection.send({ op: "edit", network, edits });
    if (undo) {
      app.undo.push({ network, label, redo: edits, undo });
      if (app.undo.length > 100) app.undo.shift();
      app.redo = [];
    }
    return r.results;
  } catch (e) {
    toast((e as Error).message);
    return null;
  } finally {
    app.busy--;
  }
}

export async function undo(): Promise<void> {
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
    toast(`Undo failed: ${(e as Error).message}`);
  } finally {
    app.busy--;
  }
}

export async function redo(): Promise<void> {
  const change = app.redo.pop();
  if (!change || change.network !== app.current) return;
  app.busy++;
  try {
    await connection.send({ op: "edit", network: change.network, edits: change.redo });
    app.undo.push(change);
  } catch (e) {
    toast(`Redo failed: ${(e as Error).message}`);
  } finally {
    app.busy--;
  }
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
export function openDevice(device: Device) {
  app.selectedDevice = posKey(device.pos);
  app.selectedNodes = [];
  app.selectedEdges = [];
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

/** Lights a connector, device or face up in the world (or turns it off); a color picks its color. */
export function setIdentify(target: Identify, on: boolean, color?: number) {
  const { label: _label, color: _color, ...bare } = target;
  return edit([{ type: "identify", ...bare, ...(color !== undefined ? { color } : {}), on }], [{ type: "identify", ...bare, on: !on }], on ? "identify" : "stop identifying");
}

export function toggleIdentify(target: Identify) {
  return setIdentify(target, !isIdentified(target));
}

export function deviceName(device: Device | undefined): string {
  return device ? device.name || device.defaultName : "Unknown device";
}

export function nodeById(sheet: Sheet | null, id: string): GraphNode | undefined {
  return sheet?.nodes?.find((n) => n.id === id);
}

export function uuid(): string {
  return crypto.randomUUID();
}
