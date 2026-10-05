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
  /** For "weighted": each branch's by the id of its wire (a missing one counts 1), so it stays with its branch. */
  weights?: Record<string, number>;
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

/** What a signal output does while its sheet is switched off. */
export interface OffBehavior {
  mode?: "hold" | "fixed" | "run";
  /** For "fixed": 0 to 15. */
  value?: number;
}

/** One way into a device node. */
export interface DeviceInput {
  resource: Resource;
  faces?: Face[];
  slots?: { first: number; last: number };
  caps?: Limit[];
  /** A redstone input: what it gives the device while the node's sheet is off. */
  while_off?: OffBehavior;
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
  /** In a blueprint, a slot: where the block it was made from sat (laying instances out from it). */
  device: GlobalPos;
  inputs?: DeviceInput[];
  outputs?: DeviceOutput[];
  /** In a blueprint, a slot: the kind of block it takes ("minecraft:furnace"). */
  block?: string;
}

/** A child sheet placed on its parent; its pins are the child's Sheet input and Sheet output nodes. */
export interface SheetNodeSettings {
  kind: "sheet";
  sheet: string;
}

/** An instance of a blueprint; its pins are that sheet's Sheet input and Sheet output nodes. */
export interface InstanceSettings {
  kind: "instance";
  sheet: string;
  /** The block filling each slot, by the slot's node id in the blueprint. */
  bindings?: Record<string, Binding>;
}

/** The block filling a slot of an instance, and how far the set is turned from the blueprint's (quarter turns clockwise). */
export interface Binding {
  device: GlobalPos;
  turns?: number;
}

/**
 * A pin on a sheet's edge, placed inside it. Wires on the sheet's node attach by {@code slot} (fixed when the pin is
 * made); {@code resource} is what it carries (none: anything).
 */
export interface SheetPinSettings {
  kind: "sheet_input" | "sheet_output";
  name?: string;
  resource?: Resource;
  slot?: number;
  /** An output pin carrying signals: what it gives while this sheet is off. */
  while_off?: OffBehavior;
}

// Signal nodes pass integers along signal wires; above 0 counts as on, and yes/no answers are 15 or 0.


/** How much of a resource a device holds, counting what matches the filter. */
export interface LevelSettings {
  kind: "level";
  resource: Resource;
  device: GlobalPos;
  faces?: Face[];
  filter?: string;
  /** What it gives: the amount (default), 0–15 between {@code low} and {@code high}, or 0–15 by fullness like a comparator. */
  output?: "amount" | "range" | "comparator";
  low?: number;
  high?: number;
  /** On a blueprint: the slot whose block it reads, by the slot node's id (each instance reads the block filling it). */
  slot?: string;
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

/** Gives {@code value} all the time (15 when not set); switched off, nothing. */
export interface ConstantSettings {
  kind: "constant";
  value?: number;
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

/** A Sequencer's row: a name, and a value per step as a hex digit ("0" nothing, "1" to "f"). */
export interface SequencerTrack {
  name?: string;
  cells?: string;
}

/**
 * Steps through {@code steps} steps of {@code step_ticks} ticks while "run" is on or unwired; "reset" goes back to the
 * first. Output 0 is the step playing (1 on); output 1 and on are its tracks, pulsing as each step starts or held.
 */
export interface SequencerSettings {
  kind: "sequencer";
  steps?: number;
  step_ticks?: number;
  loop?: boolean;
  pulse?: boolean;
  tracks?: SequencerTrack[];
}

/**
 * Flipped from the dashboard by players who can edit: {@code value} while on, 0 while off; with {@code level}, set to any
 * value from 0 to {@code value}. Kept per blueprint instance.
 */
export interface SwitchSettings {
  kind: "switch";
  value?: number;
  level?: boolean;
  /** An item the dashboard shows it with. */
  icon?: string;
}

export type DisplayStyle = "number" | "gauge" | "light" | "message";

/** A Display's text from {@code from} up (to the next message's {@code from}), in {@code color} if it has one. */
export interface DisplayMessage {
  from?: number;
  text?: string;
  color?: string;
}

/** Shows what is wired into it on the dashboard — a number (with its unit), a gauge up to {@code max}, or a light — and passes it on. */
export interface DisplaySettings {
  kind: "display";
  style?: DisplayStyle;
  unit?: string;
  max?: number;
  /** An item the dashboard shows it with. */
  icon?: string;
  /** "#rrggbb": its number, gauge or light (none: the usual). */
  color?: string;
  /** As a message: the text of the last whose {@code from} the value has reached. */
  messages?: DisplayMessage[];
}

export type AlarmSeverity = "warning" | "critical";

/** Goes off while its input passes the test (no op: above 0) for {@code delay} seconds; gives 15 (critical) or 7 (warning) while going off. */
export interface AlarmSettings {
  kind: "alarm";
  severity?: AlarmSeverity;
  message?: string;
  op?: CompareOp;
  threshold?: number;
  delay?: number;
}

export type SignalNodeSettings =
  | LevelSettings
  | CompareSettings
  | LogicSettings
  | ConstantSettings
  | TimerSettings
  | PulseSettings
  | LatchSettings
  | CounterSettings
  | DelaySettings
  | AlarmSettings
  | SequencerSettings
  | SwitchSettings
  | DisplaySettings;


export type NodeSettings =
  | FilterSettings
  | RateLimitSettings
  | SplitterSettings
  | KitSettings
  | DeviceNodeSettings
  | SheetNodeSettings
  | InstanceSettings
  | SheetPinSettings
  | SignalNodeSettings;
export type NodeKind = NodeSettings["kind"];

/** How a signal into a node's gate switches it: on while above 0, or one operation each time it rises above 0. */
export type GateMode = "while_on" | "pulse";

/** What redstone at the controller block does to the network: nothing, run only while powered, stop while powered. */
export type ControllerRedstone = "ignored" | "high" | "low";

/** The input every node has for a signal wire that switches it on and off. */
export const GATE = -1;

export interface GraphNode {
  id: string;
  settings: NodeSettings;
  label?: string;
  enabled?: boolean;
  x?: number;
  y?: number;
  gate?: GateMode;
  /** What it is for: shown in its card's tooltip, its inspector and on the dashboard. */
  note?: string;
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
  /** The network's top sheet: every other sheet sits in it, or in one inside it. Switched by the network. */
  root?: boolean;
  /** The sheet it sits in, and its sheet node there (both absent for the root and blueprints). */
  parent?: string;
  node?: string;
  /** Its own switch: its sheet node's (the network's, for the root). Sheets above it may still be off. */
  enabled?: boolean;
  interval?: number;
  priority?: number;
  /** A blueprint never runs on its own; other sheets place instances of it. */
  blueprint?: boolean;
  nodes?: GraphNode[];
  wires?: Wire[];
  errors?: SheetError[];
}

/** Something that keeps part of a sheet from running, and the node it is about (when there is one). */
export interface SheetError {
  message: string;
  node?: string;
  /** The message without the node's name in front: shown after the node itself. */
  detail?: string;
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
  /** Faces whose energy storage takes energy in, and faces it gives energy out of. */
  energyIn?: Face[];
  energyOut?: Face[];
  /** Gives out redstone (a signal source, or a comparator value). */
  givesRedstone?: boolean;
  /** Reacts to redstone (a door, lamp, piston, machine). */
  takesRedstone?: boolean;
  /** No connector that reaches it is cabled to the controller or in the antenna's range: it counts as offline. */
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
  /** What the game remembers (switches, latches, counters), by the id each runs as. */
  memory?: Record<string, number>;
  /** What is going off: its alarm nodes and the built-in alarms, critical first, then oldest first. */
  alarms?: Alarm[];
}

/**
 * One alarm going off. {@code sheet} and {@code node}: where it shows (for a node in a placed blueprint, the blueprint's
 * node); absent for a built-in alarm about the whole network. {@code since}: when it went off, ms since the epoch.
 */
export interface Alarm {
  key: string;
  severity: AlarmSeverity;
  message: string;
  sheet?: string;
  node?: string;
  since: number;
}

export interface Member {
  id: string;
  name: string;
  role: "viewer" | "user" | "editor" | "admin";
}

/**
 * Something the network charts beyond its totals: an item, fluid or chemical moved (by id), or what a device holds of a
 * resource. {@code key} names it to the server; {@code series} is its history's id.
 */
export interface ChartSeries {
  kind: "item" | "fluid" | "chemical" | "level" | "through";
  id?: string;
  device?: GlobalPos;
  resource?: Resource;
  key: string;
  series: string;
  /** A "through" chart's two series: what goes into its device, and what comes out. */
  seriesIn?: string;
  seriesOut?: string;
}

export interface Snapshot extends NetworkSummary {
  /** What is charted beyond the totals (16 at most). */
  charts?: ChartSeries[];
  /** The history ids of everything moved of each resource. */
  chartTotals?: Partial<Record<Resource, string>>;
  revision: number;
  canEdit: boolean;
  canAdmin: boolean;
  /** Whether the player may delete the network: its owner, or an operator by the bypass. */
  canDelete?: boolean;
  hasPassword: boolean;
  /** The root sheet's id. */
  root: string;
  redstone?: ControllerRedstone;
  /** What running the network costs, when the server charges for it. */
  power?: PowerState;
  /** What is lit up in the world. */
  identified?: Identify[];
  /** Antenna upgrade in the controller, 0 (none) to 3. */
  antenna?: number;
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

/** A sheet's Sheet input (or output) nodes in pin order: top to bottom, then left to right (as the server orders them). */
export function sheetPins(sheet: Sheet | undefined, input: boolean): GraphNode[] {
  const kind = input ? "sheet_input" : "sheet_output";
  return (sheet?.nodes ?? [])
    .filter((n) => n.settings.kind === kind)
    .sort((a, b) => (a.y ?? 0) - (b.y ?? 0) || (a.x ?? 0) - (b.x ?? 0) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
}

/** A pin's slot (wires on the sheet's node attach by it); pins the server hasn't numbered yet count by their order. */
export function pinSlot(node: GraphNode, index: number): number {
  const s = node.settings;
  return (s.kind === "sheet_input" || s.kind === "sheet_output") && s.slot !== undefined && s.slot >= 0 ? s.slot : index;
}

/** A pin's name: its Pin name (or a label set on it before pins had only that), else "in 2" / "out 1". */
export function pinName(node: GraphNode, index?: number): string {
  const s = node.settings;
  const named = (s.kind === "sheet_input" || s.kind === "sheet_output") && s.name ? s.name : node.label;
  return named || `${s.kind === "sheet_input" ? "in" : "out"}${index === undefined ? "" : ` ${index + 1}`}`;
}
