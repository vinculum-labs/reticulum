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

export interface PortFields {
  resource: Resource;
  device: GlobalPos;
  faces?: Face[];
  slots?: { first: number; last: number };
}

export interface SourceSettings extends PortFields {
  kind: "source";
  keep?: Limit[];
  /** 0 or absent: one full stack. */
  per_operation?: number;
  /** 0 or absent: the sheet's interval. */
  interval?: number;
  priority?: number;
}

export interface SinkSettings extends PortFields {
  kind: "sink";
  caps?: Limit[];
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

export type NodeSettings = SourceSettings | SinkSettings | FilterSettings | RateLimitSettings | SplitterSettings | KitSettings;
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
  /** 0 = main / match, 1 = reject. */
  output?: number;
  to: string;
  order?: number;
}

export interface Sheet {
  id: string;
  name: string;
  folder?: string;
  enabled?: boolean;
  interval?: number;
  priority?: number;
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

export interface Snapshot extends NetworkSummary {
  revision: number;
  canEdit: boolean;
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
