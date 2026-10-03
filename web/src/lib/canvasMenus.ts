/**
 * The canvas's right-click menus (on empty canvas, a card or pin, a wire, a box of cards) and the edits they make
 * that need nothing of the canvas but where things are: renaming, switching, a pin's type, cutting a node's wires,
 * putting a node in a wire.
 */
import ExternalLink from "@lucide/svelte/icons/external-link";
import Maximize from "@lucide/svelte/icons/maximize";
import Pencil from "@lucide/svelte/icons/pencil";
import Plus from "@lucide/svelte/icons/plus";
import Power from "@lucide/svelte/icons/power";
import PowerOff from "@lucide/svelte/icons/power-off";
import Trash from "@lucide/svelte/icons/trash";
import Unplug from "@lucide/svelte/icons/unplug";
import type { Edge, Node } from "@xyflow/svelte";
import { isPin, type NodeData } from "./graph";
import { KIND_ICON, SparklesIcon, ZapIcon } from "./icons";
import { KIND_LABEL, PIN_CARRIES, SIGNAL_KINDS, defaultSettings } from "./kinds";
import { identifyItem, openMenu, type MenuItem } from "./menu.svelte";
import { app, deviceAt, edit, openCopy, openDevice, openSheet, pinChanged, setSheetEnabled, updateNodeEdit, uuid } from "./state.svelte";
import { posKey, type Device, type GraphNode, type NodeKind, type Resource, type Sheet, type Wire } from "./types";

/** What the menus need of the canvas showing them. */
export interface Canvas {
  /** Whether what is shown can be changed (not when only viewing, or in an open copy). */
  readonly editable: boolean;
  /** A blueprint copy is open: only its slots can be filled. */
  readonly inCopy: boolean;
  readonly tidying: boolean;
  /** The open sheet. */
  readonly sheet: Sheet | null;
  /** The sheet under a point on the screen, and the point in its coordinates. */
  sheetAt(clientX: number, clientY: number): { sheet: Sheet; x: number; y: number } | null;
  /** The sheet a node or wire on the canvas is on, and a node's saved form. */
  sheetOf(id: string): Sheet | undefined;
  nodeOf(id: string): GraphNode | undefined;
  select(nodeIds: string[], edgeIds: string[]): void;
  addNode(kind: NodeKind, at: { x: number; y: number }, device: Device | null, into: Sheet, blueprintId?: string): void;
  /** Opens the block picker to put a slot at this spot. */
  pickSlot(at: { x: number; y: number }): void;
  tidy(): void;
  fit(): void;
  /** Deleted as the Delete key does (so wires go with nodes, and frames' insides with them). */
  remove(ids: { nodes?: string[]; edges?: string[] }): void;
}

/** Where a new node goes for a click at this spot: its card's corner, so the pointer lands near its header. */
function cardAt(place: { x: number; y: number }) {
  return { x: place.x - 70, y: place.y - 17 };
}

/** On empty canvas: add a node at that spot (in the sheet open there), lay out, fit. */
export function paneMenu(canvas: Canvas, event: MouseEvent) {
  const fit: MenuItem = { label: "Fit to view", icon: Maximize, run: () => canvas.fit() };
  const place = canvas.sheetAt(event.clientX, event.clientY);
  if (!place || canvas.inCopy) return openMenu(event, [fit]);
  const at = cardAt(place);
  const add = (kind: NodeKind, blueprintId?: string): MenuItem => ({
    label: KIND_LABEL[kind],
    icon: KIND_ICON[kind],
    run: () => canvas.addNode(kind, at, null, place.sheet, blueprintId),
  });
  const others = (app.snapshot?.sheets ?? []).filter((x) => x.blueprint && x.id !== place.sheet.id);
  openMenu(event, [
    canvas.editable && {
      label: "Add here",
      icon: Plus,
      items: [
        place.sheet.blueprint ? { label: "Slot…", icon: KIND_ICON.device, run: () => canvas.pickSlot(at) } : add("device"),
        add("filter"),
        add("rate_limit"),
        add("splitter"),
        add("kit"),
        { label: "Redstone", icon: ZapIcon, items: SIGNAL_KINDS.map((k) => add(k)) },
        "-",
        !place.sheet.blueprint && add("sheet"),
        others.length > 0 && {
          label: "Blueprint",
          icon: KIND_ICON.copy,
          items: others.map((b) => ({ label: b.name, icon: KIND_ICON.copy, run: () => canvas.addNode("copy", at, null, place.sheet, b.id) })),
        },
      ],
    },
    "-",
    canvas.editable && { label: "Tidy up", icon: SparklesIcon, disabled: canvas.tidying || !canvas.sheet?.nodes?.length, run: () => canvas.tidy() },
    fit,
  ]);
}

/** On a card or a pin tag: what can be done to it from here. */
export function nodeMenu(canvas: Canvas, node: Node<NodeData>, event: MouseEvent) {
  if (node.type === "sheetFrame") return paneMenu(canvas, event);
  // An open frame's body is that sheet's canvas; its header is the sheet node's.
  if (node.data.frame && !(event.target instanceof Element && event.target.closest("[data-card-header]"))) return paneMenu(canvas, event);
  const graphNode = canvas.nodeOf(node.id);
  const on = canvas.sheetOf(node.id);
  if (!graphNode || !on) return;
  if (!app.selectedNodes.includes(node.id)) canvas.select([node.id], []);
  const editable = canvas.editable;
  const s = graphNode.settings;
  const wired = (on.wires ?? []).filter((w) => w.from === node.id || w.to === node.id);
  const remove: MenuItem = { label: "Delete", icon: Trash, danger: true, shortcut: "Del", disabled: !editable, run: () => canvas.remove({ nodes: [node.id] }) };
  const rename = editable && { label: "Rename", icon: Pencil, run: () => nameNode(canvas, node.id) };
  if (isPin(graphNode)) {
    const resource = s.kind === "sheet_input" || s.kind === "sheet_output" ? s.resource : undefined;
    return openMenu(event, [
      rename,
      editable && {
        label: "Carries",
        items: PIN_CARRIES.map((c) => ({ label: c.label, checked: (resource ?? "") === c.value, run: () => savePin(on, graphNode, (c.value || undefined) as Resource | undefined) })),
      },
      "-",
      remove,
    ]);
  }
  const device = s.kind === "device" && !on.blueprint ? deviceAt(posKey(s.device)) : undefined;
  const held = s.kind === "sheet" || s.kind === "copy" ? app.snapshot?.sheets.find((x) => x.id === s.sheet) : undefined;
  const enabled = s.kind === "sheet" ? held?.enabled !== false : graphNode.enabled !== false;
  openMenu(event, [
    held && {
      label: "Open",
      icon: ExternalLink,
      run: () => (s.kind === "copy" && !on.blueprint ? openCopy(on.id, node.id) : openSheet(held.id)),
    },
    rename,
    editable && { label: enabled ? "Turn off" : "Turn on", icon: enabled ? PowerOff : Power, run: () => switchNode(on, graphNode, held, !enabled) },
    device && identifyItem(device.pos),
    device && { label: "Device panel…", icon: KIND_ICON.device, run: () => openDevice(device) },
    editable && wired.length > 0 && { label: wired.length === 1 ? "Disconnect its wire" : `Disconnect all ${wired.length} wires`, icon: Unplug, run: () => disconnectAll(on, node.id) },
    "-",
    remove,
  ]);
}

/** On a wire: put a node in it, or remove it. */
export function edgeMenu(canvas: Canvas, edge: Edge, event: MouseEvent) {
  const on = canvas.sheetOf(edge.id);
  const wire = on?.wires?.find((w) => w.id === edge.id);
  if (!on || !wire) return;
  if (!app.selectedEdges.includes(edge.id)) canvas.select([], [edge.id]);
  const place = canvas.sheetAt(event.clientX, event.clientY);
  const at = place && place.sheet.id === on.id ? cardAt(place) : { x: 0, y: 0 };
  const insert = (kind: NodeKind): MenuItem => ({ label: KIND_LABEL[kind], icon: KIND_ICON[kind], run: () => insertOnWire(canvas, on, wire, kind, at) });
  openMenu(event, [
    canvas.editable && !(edge.data as { signal?: boolean } | undefined)?.signal && {
      label: "Insert",
      icon: Plus,
      items: [insert("filter"), insert("rate_limit"), insert("splitter")],
    },
    "-",
    { label: "Delete", icon: Trash, danger: true, shortcut: "Del", disabled: !canvas.editable, run: () => canvas.remove({ edges: [edge.id] }) },
  ]);
}

/** On a box of several selected cards. */
export function selectionMenu(canvas: Canvas, nodes: Node[], event: MouseEvent) {
  openMenu(event, [
    { label: `Delete ${nodes.length} nodes`, icon: Trash, danger: true, shortcut: "Del", disabled: !canvas.editable, run: () => canvas.remove({ nodes: nodes.map((n) => n.id) }) },
  ]);
}

/** Selects a node and puts the keyboard in its Name box in the side panel. */
function nameNode(canvas: Canvas, id: string) {
  canvas.select([id], []);
  app.panel = null;
  app.nameNode = id;
}

/** A node's switch (a sheet node's is its sheet's). */
function switchNode(on: Sheet, node: GraphNode, held: Sheet | undefined, enabled: boolean) {
  if (node.settings.kind === "sheet" && held) return setSheetEnabled(held, enabled);
  updateNodeEdit(on.id, node, { ...node, enabled }, enabled ? "switch on" : "switch off");
}

function savePin(on: Sheet, pin: GraphNode, resource: Resource | undefined) {
  updateNodeEdit(on.id, pin, pinChanged(pin, { resource }), "pin type");
}

/** Removes every wire on a node, as one undoable change. */
function disconnectAll(on: Sheet, id: string) {
  const wires = (on.wires ?? []).filter((w) => w.from === id || w.to === id);
  edit(
    wires.map((w) => ({ type: "disconnect", sheet: on.id, wire: w.id })),
    wires.map((wire) => ({ type: "connect", sheet: on.id, wire })),
    "disconnect",
  );
}

/**
 * Puts a new node in a wire: the wire goes, and two take its place, from where it came into the node (keeping its
 * place among a splitter's branches) and from the node to where it went. One undoable change; the node is selected.
 */
async function insertOnWire(canvas: Canvas, on: Sheet, wire: Wire, kind: NodeKind, at: { x: number; y: number }) {
  const node: GraphNode = { id: uuid(), settings: defaultSettings(kind, null, "item"), x: Math.round(at.x), y: Math.round(at.y) };
  const into: Wire = { id: uuid(), from: wire.from, ...(wire.output ? { output: wire.output } : {}), to: node.id, ...(wire.order !== undefined ? { order: wire.order } : {}) };
  const out: Wire = { id: uuid(), from: node.id, to: wire.to, ...(wire.input ? { input: wire.input } : {}) };
  const ok = await edit(
    [
      { type: "disconnect", sheet: on.id, wire: wire.id },
      { type: "addNode", sheet: on.id, node },
      { type: "connect", sheet: on.id, wire: into },
      { type: "connect", sheet: on.id, wire: out },
    ],
    [
      { type: "removeNode", sheet: on.id, node: node.id },
      { type: "connect", sheet: on.id, wire },
    ],
    `insert ${KIND_LABEL[kind].toLowerCase()}`,
  );
  if (ok) canvas.select([node.id], []);
}
