// Shapes of the JSON the server sends and accepts. They mirror the Java codecs (Sheet, Node, NodeSettings, Wire);
// optional fields are left out by the server when they hold their default value.

export type Resource = "item" | "fluid" | "energy";
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

export interface SplitterSettings {
  kind: "splitter";
  mode?: "round_robin" | "priority";
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

export type NodeSettings =
  | FilterSettings
  | RateLimitSettings
  | SplitterSettings
  | KitSettings
  | DeviceNodeSettings
  | SubSheetSettings
  | SheetPinSettings;
export type NodeKind = NodeSettings["kind"];

export interface GraphNode {
  id: string;
  settings: NodeSettings;
  label?: string;
  enabled?: boolean;
  x?: number;
  y?: number;
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
}

export interface Connector {
  id: string;
  pos: GlobalPos;
  name?: string;
  devices: GlobalPos[];
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
  /** Only sent to admins. */
  members?: Member[];
  controller?: GlobalPos;
  sheets: Sheet[];
  devices: Device[];
  connectors: Connector[];
  filters: Record<string, string>;
  overflow: { items: { id: string; count: number }[]; fluids: { id: string; amount: number }[] };
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
