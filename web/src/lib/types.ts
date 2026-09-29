// Shapes of the JSON the server sends and accepts. They mirror the Java codecs (Sheet, Node, NodeSettings, Wire);
// optional fields are left out by the server when they hold their default value.

/** What a device pin carries: resources that flow, redstone signals, or an ME (AE2) link. */
export type Resource = "item" | "fluid" | "energy" | "chemical" | "redstone" | "me";
export type Face = "down" | "up" | "north" | "south" | "west" | "east" | "unsided";
export const FACES: Face[] = ["up", "down", "north", "south", "west", "east", "unsided"];
export const SIDES: Face[] = ["up", "down", "north", "south", "west", "east"];

export interface GlobalPos {
  dimension: string;
  pos: [number, number, number];
}

export type Scope = "total" | "per_type" | "filter";

export interface Limit {
  scope: Scope;
  amount: number;
  filter?: string;
}

export interface FilterSettings {
  kind: "filter";
  expression: string;
}

export interface RateLimitSettings {
  kind: "rate_limit";
  per_second: number;
}

export type SplitMode = "round_robin" | "priority" | "weighted" | "nearest";

export interface SplitterSettings {
  kind: "splitter";
  mode?: SplitMode;
  /** For "weighted": one per output in wire order (missing ones count 1). */
  weights?: number[];
}

export interface KitLine {
  filter: string;
  count: number;
}

export interface KitSettings {
  kind: "kit";
  lines: KitLine[];
  max_kits?: number;
}

/** Resource, faces and slot range of one way in or out of a device. */
export interface Side {
  resource: Resource;
  faces?: Face[];
  slots?: { first: number; last: number };
}

/** One way into a device node. */
export interface DeviceInput {
  resource: Resource;
  faces?: Face[];
  slots?: { first: number; last: number };
  caps?: Limit[];
}

/** One way out of a device node; the same options as a source. */
export interface DeviceOutput {
  resource: Resource;
  faces?: Face[];
  slots?: { first: number; last: number };
  keep?: Limit[];
  per_operation?: number;
  interval?: number;
  priority?: number;
  /** A redstone output: read the comparator value instead of the redstone signal. */
  comparator?: boolean;
}

/** A block that is both fed and emptied: numbered inputs (left) and outputs (right). */
export interface DeviceNodeSettings {
  kind: "device";
  device: GlobalPos;
  inputs?: DeviceInput[];
  outputs?: DeviceOutput[];
}

/** A copy of a sub-sheet; its pins are that sheet's Sheet input and Sheet output nodes. */
export interface SubSheetSettings {
  kind: "subsheet";
  sheet: string;
}

/** A named pin of a sub-sheet, placed inside it. */
export interface SheetPinSettings {
  kind: "sheet_input" | "sheet_output";
  name?: string;
}

// Signal nodes pass integers along signal wires; above 0 counts as on, and yes/no answers are 15 or 0.


/** How much of a resource a device holds, counting what matches the filter. */
export interface LevelSettings {
  kind: "level";
  resource: Resource;
  device: GlobalPos;
  faces?: Face[];
  filter?: string;
}

export type CompareOp = "lt" | "le" | "gt" | "ge" | "eq" | "ne";

/** A (input 0) against B (input 1), or against the constant when B isn't wired. */
export interface CompareSettings {
  kind: "compare";
  op?: CompareOp;
  constant?: number;
}

export type LogicOp = "and" | "or" | "not" | "xor" | "nand" | "xnor";

export interface LogicSettings {
  kind: "logic";
  op?: LogicOp;
}

export interface TimerSettings {
  kind: "timer";
  interval?: number;
}

export interface PulseSettings {
  kind: "pulse";
  length?: number;
}

export interface LatchSettings {
  kind: "latch";
}

export interface CounterSettings {
  kind: "counter";
}

export interface DelaySettings {
  kind: "delay";
  ticks?: number;
}

/** Transmits its input as a named network signal. */
export interface TransmitSettings {
  kind: "transmit";
  name?: string;
}

/** Receives a named network signal. */
export interface ReceiveSettings {
  kind: "receive";
  name?: string;
}

export type SignalNodeSettings =
  | LevelSettings
  | CompareSettings
  | LogicSettings
  | TimerSettings
  | PulseSettings
  | LatchSettings
  | CounterSettings
  | DelaySettings
  | TransmitSettings
  | ReceiveSettings;


export type NodeSettings =
  | FilterSettings
  | RateLimitSettings
  | SplitterSettings
  | KitSettings
  | DeviceNodeSettings
  | SubSheetSettings
  | SheetPinSettings
  | SignalNodeSettings;
export type NodeKind = NodeSettings["kind"];

export type RedstoneMode = "ignored" | "high" | "low" | "pulse";
export type RedstoneSource = "default" | "connector" | "face" | "signal";

/** A redstone condition: the thing runs only while it allows (and its on/off button is on). */
export interface Redstone {
  mode?: RedstoneMode;
  source?: RedstoneSource;
  device?: GlobalPos;
  face?: Face;
  connector?: string;
  signal?: string;
}

/** The input every node has for a signal wire that switches it on and off. */
export const GATE = -1;

export interface GraphNode {
  id: string;
  settings: NodeSettings;
  label?: string;
  enabled?: boolean;
  x?: number;
  y?: number;
  redstone?: Redstone;
}

export interface Wire {
  id: string;
  from: string;
  /** Which output: 0 = main / match, 1 = reject; a device node's output number. */
  output?: number;
  to: string;
  /** Which input on a device node. */
  input?: number;
  order?: number;
}

export interface Sheet {
  id: string;
  name: string;
  folder?: string;
  enabled?: boolean;
  interval?: number;
  priority?: number;
  /** A sub-sheet never runs on its own; other sheets place copies of it. */
  subsheet?: boolean;
  redstone?: Redstone;
  nodes?: GraphNode[];
  wires?: Wire[];
  errors?: string[];
}

export interface Device {
  pos: GlobalPos;
  name?: string;
  defaultName: string;
  block: string;
  icon?: string;
  state: "active" | "orphaned" | "missing";
  faceNames: Partial<Record<Face, string>>;
  resources?: Partial<Record<Resource, Face[]>>;
  loaded: boolean;
  /** Gives out redstone (a signal source, or a comparator value). */
  givesRedstone?: boolean;
  /** Reacts to redstone (a door, lamp, piston, machine). */
  takesRedstone?: boolean;
  /** Only wireless connectors the antenna doesn't reach reach it: it counts as offline. */
  outOfRange?: boolean;
}

export interface Connector {
  id: string;
  pos: GlobalPos;
  name?: string;
  devices: GlobalPos[];
  /** Sides with an ME endpoint (facing an AE2 cable or machine); only sent when AE2 is installed. */
  me?: Face[];
  /** How it reaches the controller: through cables, wirelessly, or not at all. */
  reach?: "wired" | "wireless" | "out_of_range";
}

export interface NetworkSummary {
  id: string;
  name: string;
  access: "public" | "password" | "private";
  owner: string;
  role: "viewer" | "user" | "editor" | "admin" | "owner" | "none";
  enabled: boolean;
  running: boolean;
}

export interface Member {
  id: string;
  name: string;
  role: "viewer" | "user" | "editor" | "admin";
}

export interface Snapshot extends NetworkSummary {
  revision: number;
  canEdit: boolean;
  canAdmin: boolean;
  hasPassword: boolean;
  disabledFolders: string[];
  redstone?: Redstone;
  /** What running the network costs, when the server charges for it. */
  power?: PowerState;
  /** What is lit up in the world. */
  identified?: Identify[];
  /** Antenna upgrade in the controller, 0 (none) to 3. */
  antenna?: number;
  /** Redstone conditions of folders, by folder path. */
  folderRedstone?: Record<string, Redstone>;
  /** Only sent to admins. */
  members?: Member[];
  controller?: GlobalPos;
  sheets: Sheet[];
  devices: Device[];
  connectors: Connector[];
  filters: Record<string, string>;
  overflow: { items: { id: string; count: number }[]; fluids: { id: string; amount: number }[]; chemicals: { id: string; amount: number }[] };
}

/** Channel use of an ME port: through it, and the fullest cable on the way to the ME controller. */
export interface MeUsage {
  connected: boolean;
  /** Channels going through this port (the AE2 machines behind it). */
  through: number;
  /** The fullest cable on the way to the controller: channels in use and the most it carries. */
  used: number;
  max: number;
}

export interface PowerState {
  /** Whether power costs anything on this server; when not, nothing about power is shown. */
  enabled: boolean;
  powered: boolean;
  stored: number;
  capacity: number;
  /** FE per tick. */
  upkeep: number;
}

/** Something lit up in the world so it can be found: a connector, a device, or one face of a device. */
export interface Identify {
  kind: "connector" | "device" | "face";
  connector?: string;
  device?: GlobalPos;
  face?: Face;
  color?: number;
  /** Sent by the server: the name shown for it. */
  label?: string;
}

export type Edit = { type: string } & Record<string, unknown>;

export function posKey(p: GlobalPos): string {
  return `${p.dimension}|${p.pos.join(",")}`;
}

export function samePos(a: GlobalPos, b: GlobalPos): boolean {
  return posKey(a) === posKey(b);
}

/** A sub-sheet's Sheet input (or output) nodes in pin order: top to bottom, then left to right (as the server orders them). */
export function sheetPins(sheet: Sheet | undefined, input: boolean): GraphNode[] {
  const kind = input ? "sheet_input" : "sheet_output";
  return (sheet?.nodes ?? [])
    .filter((n) => n.settings.kind === kind)
    .sort((a, b) => (a.y ?? 0) - (b.y ?? 0) || (a.x ?? 0) - (b.x ?? 0) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
}

export function pinName(node: GraphNode, index: number): string {
  const s = node.settings;
  return (s.kind === "sheet_input" || s.kind === "sheet_output") && s.name ? s.name : `${s.kind === "sheet_input" ? "in" : "out"} ${index + 1}`;
}
