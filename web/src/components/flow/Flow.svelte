<script lang="ts">
  import { onDestroy, untrack } from "svelte";
  import { modals } from "../../lib/dialog.svelte";
  import SheetErrors from "../inspector/SheetErrors.svelte";
  import SheetOffNotice from "./SheetOffNotice.svelte";
  import { KIND_ICON, SparklesIcon, SplineIcon, ZapIcon } from "../../lib/icons";
  import MenuButton from "./MenuButton.svelte";
  import {
    SvelteFlow,
    Background,
    Controls,
    MiniMap,
    Panel,
    useSvelteFlow,
    type Edge,
    type ConnectionLineType,
    type Node,
    type Connection,
  } from "@xyflow/svelte";
  import {
    app,
    createSheet,
    currentSheet,
    deviceAt,
    edit,
    nodeTitle,
    openCopy,
    openCopyNode,
    openSheet,
    resync,
    setBindings,
    toast,
    uuid,
    holdMetrics,
    setWireStyle,
    WIRE_STYLE_LABEL,
    type WireStyle,
  } from "../../lib/state.svelte";
  import { copiesOf, slotName } from "../../lib/blueprints";
  import { confirmDialog } from "../../lib/dialog.svelte";
  import { heldSheet, descendants } from "../../lib/tree";
  import Breadcrumbs from "./Breadcrumbs.svelte";
  import { handleIndex, pinResource, toFlow, type FlowView, type NodeData } from "../../lib/graph";
  import { crossWire } from "../../lib/crossing";
  import { KIND_HINT, KIND_LABEL, SIGNAL_KINDS, defaultSettings, isSignal, offeredResources } from "../../lib/kinds";
  import type { Device, Edit, GraphNode, NodeKind, Resource, Sheet, Wire } from "../../lib/types";
  import GraphNodeView from "./GraphNode.svelte";
  import { layout, type PinPositions } from "../../lib/layout";

  const nodeTypes = { graph: GraphNodeView };
  const flow = useSvelteFlow();

  let nodes = $state.raw<Node<NodeData>[]>([]);
  let edges = $state.raw<Edge[]>([]);
  /** Which sheet each node and wire on the canvas is on, and the frames open (sheets shown in place). */
  let view: FlowView = { nodes: [], edges: [], owner: new Map(), frames: new Map() };
  /** The card sizes the frames were last sized with. */
  let sizedWith = new Map<string, { width: number; height: number }>();
  let dragging = false;
  let wiring = $state(false);
  let container: HTMLDivElement | undefined = $state();

  const sheet = $derived(currentSheet());
  const canEdit = $derived(app.snapshot?.canEdit ?? false);
  /** A blueprint copy open on the canvas: its blueprint shows, read-only but for filling its slots. */
  const copy = $derived(app.copy ? openCopyNode() : null);
  const editable = $derived(canEdit && !copy);
  /** How many copies the open blueprint has: editing it changes them all. */
  const copyCount = $derived(sheet?.blueprint && app.snapshot ? copiesOf(app.snapshot, sheet.id).length : 0);

  // A node opened from the dashboard is brought into view once Svelte Flow has measured it.
  $effect(() => {
    const id = app.focusNode;
    if (!id || !nodes.some((n) => n.id === id && n.measured)) return;
    untrack(() => {
      app.focusNode = null;
      flow.fitView({ nodes: [{ id }], duration: 300, maxZoom: 1.2 });
    });
  });

  // A drag interrupted by the canvas going away (leaving the world) mustn't leave metrics held for good.
  onDestroy(() => holdMetrics(false));

  // Rebuild from the server's snapshot, and on every metrics update, except mid-drag; and when an edit was refused,
  // so what Svelte Flow changed on its own (a new wire, a moved node) goes back to what the server has.
  $effect(() => {
    void resync.count;
    const s = sheet;
    const devices = app.snapshot?.devices ?? [];
    const activity = {
      rates: app.rates,
      fiveMinutes: app.fiveMinutes,
      status: app.status,
      signals: app.signals,
      me: app.me,
    };
    // The selection is carried into the rebuilt nodes but isn't a reason to rebuild: the canvas already shows it.
    const selected = untrack(() => new Set([...app.selectedNodes, ...app.selectedEdges]));
    const unit = app.rateUnit;
    const open = new Set(app.frames);
    if (!s || dragging) return;
    // Frames are sized from their cards as last measured (a guess until then; see below).
    const sizes = new Map<string, { width: number; height: number }>();
    for (const n of untrack(() => nodes)) if (n.measured?.width && !n.data.frame) sizes.set(n.id, { width: n.measured.width, height: n.measured.height ?? 0 });
    const bindings = copy ? (copy.node.settings.bindings ?? {}) : undefined;
    const built = toFlow(s, app.snapshot?.sheets ?? [], devices, activity, selected, unit, app.wireStyle, open, sizes, bindings);
    view = built;
    sizedWith = sizes;
    rebuilding = true;
    // Unchanged nodes and wires keep their objects, so Svelte Flow skips them (it compares by identity) instead of
    // re-rendering and re-measuring the whole sheet on every metrics update.
    const oldNodes = new Map(untrack(() => nodes).map((n) => [n.id, n]));
    const oldEdges = new Map(untrack(() => edges).map((e) => [e.id, e]));
    nodes = built.nodes.map((n) => {
      const old = oldNodes.get(n.id);
      return old && sameNode(old, n) ? old : n;
    });
    edges = built.edges.map((e) => {
      const old = oldEdges.get(e.id);
      return old && sameEdge(old, e) ? old : e;
    });
    queueMicrotask(() => (rebuilding = false));
  });

  // Cards in a frame measured at another size than the frame was sized for: size it again (settles in a pass or two).
  $effect(() => {
    const list = nodes;
    if (!list.some((n) => n.parentId)) return;
    const off = list.some((n) => {
      if (!n.parentId || n.data.frame || !n.measured?.width) return false;
      const used = sizedWith.get(n.id);
      return !used || Math.abs(used.width - n.measured.width) > 1 || Math.abs(used.height - (n.measured.height ?? 0)) > 1;
    });
    if (off) untrack(() => resync.count++);
  });

  // Selections made here in code (a node just added, a device picked) shown on the canvas without a rebuild;
  // nodes are only replaced when their highlight actually differs.
  $effect(() => {
    const selected = new Set([...app.selectedNodes, ...app.selectedEdges]);
    untrack(() => {
      if (nodes.some((n) => !!n.selected !== selected.has(n.id))) {
        nodes = nodes.map((n) => (!!n.selected === selected.has(n.id) ? n : { ...n, selected: selected.has(n.id) }));
      }
      if (edges.some((e) => !!e.selected !== selected.has(e.id))) {
        edges = edges.map((e) => (!!e.selected === selected.has(e.id) ? e : { ...e, selected: selected.has(e.id) }));
      }
    });
  });

  /** Set while new nodes are being handed to the canvas, which may report the selection as empty meanwhile. */
  let rebuilding = false;

  function sameValue(a: unknown, b: unknown): boolean {
    return a === b || (typeof a === "object" && typeof b === "object" && JSON.stringify(a) === JSON.stringify(b));
  }

  function sameNode(a: Node<NodeData>, b: Node<NodeData>): boolean {
    if (a.type !== b.type || a.parentId !== b.parentId || !!a.selected !== !!b.selected || a.position.x !== b.position.x || a.position.y !== b.position.y) return false;
    const keys = new Set([...Object.keys(a.data), ...Object.keys(b.data)]) as Set<keyof NodeData>;
    for (const key of keys) if (!sameValue(a.data[key], b.data[key])) return false;
    return true;
  }

  function sameEdge(a: Edge, b: Edge): boolean {
    return (
      a.source === b.source &&
      a.target === b.target &&
      a.sourceHandle === b.sourceHandle &&
      a.targetHandle === b.targetHandle &&
      a.type === b.type &&
      !!a.selected === !!b.selected &&
      a.animated === b.animated &&
      a.label === b.label &&
      a.style === b.style
    );
  }

  function sameIds(a: string[], b: string[]): boolean {
    return a.length === b.length && a.every((id, i) => id === b[i]);
  }

  /**
   * Only real changes count: the canvas reports the selection again whenever its nodes are rebuilt (every network
   * update), and a fresh but equal list, or a passing empty one mid-rebuild, would remount the side panel.
   */
  function selectionChange({ nodes: n, edges: e }: { nodes: Node[]; edges: Edge[] }) {
    const nodeIds = n.map((x) => x.id);
    const edgeIds = e.map((x) => x.id);
    if (rebuilding && nodeIds.length === 0 && edgeIds.length === 0) return;
    const changed = !sameIds(nodeIds, app.selectedNodes) || !sameIds(edgeIds, app.selectedEdges);
    if (!sameIds(nodeIds, app.selectedNodes)) app.selectedNodes = nodeIds;
    if (!sameIds(edgeIds, app.selectedEdges)) app.selectedEdges = edgeIds;
    // Picking something else on the canvas closes an open device; the same selection reported again doesn't.
    if (changed && (n.length || e.length)) app.selectedDevice = null;
  }

  /** The sheet a node or wire on the canvas is on: this one, or one open in a frame. */
  function sheetOf(id: string): Sheet | undefined {
    const owner = view.owner.get(id);
    return owner ? app.snapshot?.sheets.find((x) => x.id === owner) : undefined;
  }

  function nodeOf(id: string): GraphNode | undefined {
    return sheetOf(id)?.nodes?.find((n) => n.id === id);
  }

  /** The frames around a node on the canvas, innermost first. */
  function framesAround(id: string): string[] {
    const out: string[] = [];
    for (let at = nodes.find((n) => n.id === id)?.parentId; at && out.length < 16; at = nodes.find((n) => n.id === at)?.parentId) out.push(at);
    return out;
  }

  /**
   * Signals go into signal inputs or a node's gate; resources go into resource inputs. A sheet's pins carry what
   * their type says; an untyped pin carries anything, so the server checks those.
   */
  function validConnection(c: Connection | Edge): boolean {
    const from = nodeOf(c.source);
    const to = nodeOf(c.target);
    if (!from || !to || c.source === c.target) return false;
    // A frame's own pins are for wires outside it; inside, wire to its Sheet input and output nodes.
    if (framesAround(c.source).includes(c.target) || framesAround(c.target).includes(c.source)) return false;
    const sheets = app.snapshot?.sheets ?? [];
    const untypedPin = (node: GraphNode, p: Resource | null) =>
      !p && ["sheet_input", "sheet_output", "sheet", "copy"].includes(node.settings.kind);
    // What each end carries: a pin's resource, "signal" for signal nodes, "flow" for routing nodes.
    const fromPin = pinResource(from, "out", handleIndex(c.sourceHandle), sheets);
    const toPin = c.targetHandle === "gate" ? "signal" : pinResource(to, "in", handleIndex(c.targetHandle), sheets);
    const kindOf = (node: GraphNode, p: Resource | null | "signal") =>
      p === "redstone" || p === "signal" ? "signal" : p === "me" ? "me" : p ? "flow" : isSignal(node.settings.kind) ? "signal" : "flow";
    if (untypedPin(from, fromPin) || (toPin !== "signal" && untypedPin(to, toPin))) return true;
    // Two typed resource pins must carry the same thing.
    if (fromPin && toPin && toPin !== "signal" && kindOf(from, fromPin) === "flow" && kindOf(to, toPin) === "flow") return fromPin === toPin;
    return kindOf(from, fromPin) === kindOf(to, toPin);
  }

  /**
   * Wires two pins. Across frame edges (between sheets open in place) each edge crossed gets a pin, all in one
   * change.
   */
  async function connect(c: Connection) {
    const from = sheetOf(c.source);
    const to = sheetOf(c.target);
    if (!from || !to || !app.snapshot) return;
    const output = handleIndex(c.sourceHandle);
    const input = handleIndex(c.targetHandle);
    if (from.id !== to.id) {
      const crossing = crossWire(app.snapshot, { sheet: from, node: c.source, index: output }, { sheet: to, node: c.target, index: input }, nodeTitle(nodeOf(c.source)), uuid);
      if (!crossing) {
        toast("Those two can't be wired together.");
        resync.count++;
        return;
      }
      await edit(crossing.edits, crossing.undo, "connect across sheets");
      return;
    }
    const order = (from.wires ?? []).filter((w) => w.from === c.source && (w.output ?? 0) === output).length;
    const wire: Wire = { id: uuid(), from: c.source, output, to: c.target, input, order };
    await edit([{ type: "connect", sheet: from.id, wire }], [{ type: "disconnect", sheet: from.id, wire: wire.id }], "connect");
  }

  /**
   * Deletes what was selected. A sheet node takes its sheet and everything in it along, which can't be undone, so it
   * asks first; the rest is undoable as usual.
   */
  async function remove({ nodes: gone, edges: cut }: { nodes: Node[]; edges: Edge[] }) {
    if (!sheet) return;
    const held = gone.map((n) => heldSheet(app.snapshot, nodeOf(n.id))).filter((x): x is NonNullable<typeof x> => !!x);
    if (held.length) {
      const inside = held.reduce((count, h) => count + descendants(app.snapshot!, h).length, 0);
      const ok = await confirmDialog({
        title: held.length === 1 ? `Delete the sheet “${held[0].name}”?` : `Delete ${held.length} sheets?`,
        message: `Everything in ${held.length === 1 ? "it" : "them"}${inside ? `, ${inside} sheet${inside === 1 ? "" : "s"} inside included,` : ""} goes too. This can't be undone.`,
        confirm: held.length === 1 ? "Delete sheet" : "Delete sheets",
        danger: true,
      });
      if (!ok) {
        resync.count++;
        return;
      }
    }
    // What sits in a sheet being deleted goes with it (Svelte Flow hands over a frame's cards along with it).
    const deleted = new Set(held.flatMap((h) => [h.id, ...descendants(app.snapshot!, h).map((x) => x.id)]));
    const goneIds = new Set(gone.map((n) => n.id));
    const edits: Edit[] = [];
    const undo: Edit[] = [];
    const touchedSheets = new Map<string, Sheet>();
    for (const n of gone) {
      const on = sheetOf(n.id);
      const node = nodeOf(n.id);
      if (!on || !node || deleted.has(on.id)) continue;
      touchedSheets.set(on.id, on);
      edits.push({ type: "removeNode", sheet: on.id, node: n.id });
      // A sheet node can't come back (its sheet is gone with it).
      if (node.settings.kind !== "sheet") undo.push({ type: "addNode", sheet: on.id, node });
    }
    for (const e of cut) {
      const on = sheetOf(e.id);
      if (on && !deleted.has(on.id)) touchedSheets.set(on.id, on);
    }
    // Restore every wire that touched a removed node, plus wires cut on their own.
    for (const on of touchedSheets.values()) {
      for (const w of on.wires ?? []) {
        const touched = goneIds.has(w.from) || goneIds.has(w.to);
        const cutAlone = cut.some((e) => e.id === w.id) && !touched;
        if (cutAlone) edits.push({ type: "disconnect", sheet: on.id, wire: w.id });
        if (touched || cutAlone) undo.push({ type: "connect", sheet: on.id, wire: w });
      }
    }
    app.selectedNodes = [];
    app.selectedEdges = [];
    await edit(edits, undo, "delete");
  }

  async function dragStop({ nodes: moved }: { nodes: Node[] }) {
    dragging = false;
    if (!sheet || moved.length === 0) {
      holdMetrics(false);
      return;
    }
    const { edits, undo } = moves(moved);
    await edit(edits, undo, "move");
    // Only now: held metrics applied before the move is saved would rebuild the canvas from the old positions, and
    // the node would jump back for a moment.
    holdMetrics(false);
  }

  /**
   * Saves where cards were dragged to, on the sheet each is on (a card in a frame: in its sheet's own coordinates).
   * A frame whose contents' corner moved moves by as much, so its other cards stay where they are on screen.
   */
  function moves(moved: Node[]): { edits: Edit[]; undo: Edit[] } {
    const placed = new Map<string, Map<string, { x: number; y: number }>>();
    const put = (sheetId: string, id: string, x: number, y: number) => {
      if (!placed.has(sheetId)) placed.set(sheetId, new Map());
      placed.get(sheetId)!.set(id, { x: Math.round(x), y: Math.round(y) });
    };
    for (const n of moved) {
      const on = sheetOf(n.id);
      if (!on) continue;
      const frame = n.parentId ? view.frames.get(n.parentId) : undefined;
      put(on.id, n.id, n.position.x + (frame?.dx ?? 0), n.position.y + (frame?.dy ?? 0));
    }
    // Frames whose contents moved, innermost first: moving a frame can shift the one around it in turn.
    const settled = new Set<string>();
    for (let pass = 0; pass < 8; pass++) {
      let shifted = false;
      for (const [frameId, frame] of view.frames) {
        const inside = placed.get(frame.sheet);
        const held = app.snapshot?.sheets.find((x) => x.id === frame.sheet);
        const outer = sheetOf(frameId);
        const node = nodeOf(frameId);
        if (!inside || !held?.nodes?.length || !outer || !node || settled.has(frameId)) continue;
        settled.add(frameId);
        const spot = (n: GraphNode) => inside.get(n.id) ?? { x: n.x ?? 0, y: n.y ?? 0 };
        const dx = Math.min(...held.nodes.map((n) => spot(n).x)) - Math.min(...held.nodes.map((n) => n.x ?? 0));
        const dy = Math.min(...held.nodes.map((n) => spot(n).y)) - Math.min(...held.nodes.map((n) => n.y ?? 0));
        if (dx || dy) {
          const at = placed.get(outer.id)?.get(frameId) ?? { x: node.x ?? 0, y: node.y ?? 0 };
          put(outer.id, frameId, at.x + dx, at.y + dy);
          shifted = true;
        }
      }
      if (!shifted) break;
    }
    const edits: Edit[] = [];
    const undo: Edit[] = [];
    for (const [sheetId, spots] of placed) {
      const on = app.snapshot?.sheets.find((x) => x.id === sheetId);
      if (!on) continue;
      const positions = [...spots].map(([id, p]) => ({ id, ...p }));
      const before = positions.map((p) => {
        const node = on.nodes?.find((n) => n.id === p.id);
        return { id: p.id, x: node?.x ?? 0, y: node?.y ?? 0 };
      });
      edits.push({ type: "moveNodes", sheet: sheetId, positions });
      undo.push({ type: "moveNodes", sheet: sheetId, positions: before });
    }
    return { edits, undo };
  }

  function offered(device: Device): Resource {
    return offeredResources(device.resources)[0] ?? "item";
  }

  async function addNode(kind: NodeKind, at: { x: number; y: number }, device: Device | null = null, into: Sheet | null = sheet) {
    if (!into) return;
    if (kind === "sheet") {
      // A new sheet inside this one, its node where asked; it stays closed until double-clicked.
      const id = await createSheet(sheet, `Sheet ${app.snapshot?.sheets.length ?? 1}`, at, false);
      if (id) {
        const placed = app.snapshot?.sheets.find((s) => s.id === id)?.node;
        if (placed) app.selectedNodes = [placed];
      }
      return;
    }
    const needsDevice = kind === "device" || kind === "level";
    if (into.blueprint && kind === "device" && device) {
      // A device dropped on a blueprint makes a slot for its kind of block (it isn't tied to that block).
      const node: GraphNode = { id: uuid(), settings: { ...defaultSettings(kind, device, offered(device)), block: device.block } as GraphNode["settings"], x: Math.round(at.x), y: Math.round(at.y) };
      const ok = await edit([{ type: "addNode", sheet: into.id, node }], [{ type: "removeNode", sheet: into.id, node: node.id }], "add slot");
      if (ok) app.selectedNodes = [node.id];
      return;
    }
    if (needsDevice && !device) {
      device = (app.selectedDevice && deviceAt(app.selectedDevice)) || app.snapshot?.devices.find((d) => d.state === "active") || null;
      if (!device) {
        toast("There are no devices on this network yet.");
        return;
      }
    }
    const blueprint = kind === "copy" ? blueprints[0]?.id : kind === "network" ? switchable[0]?.id : undefined;
    if (kind === "copy" && !blueprint) {
      toast("Make a blueprint first: from a sheet (in its settings), or New blueprint in the list.");
      return;
    }
    if (kind === "network" && !blueprint) {
      toast("No other network to switch: you must be an admin of one, and it mustn't switch this one already.");
      return;
    }
    const node: GraphNode = {
      id: uuid(),
      settings: defaultSettings(kind, device, device ? offered(device) : "item", blueprint),
      x: Math.round(at.x),
      y: Math.round(at.y),
    };
    const ok = await edit([{ type: "addNode", sheet: into.id, node }], [{ type: "removeNode", sheet: into.id, node: node.id }], "add node");
    if (ok) {
      app.selectedNodes = [node.id];
      app.selectedEdges = [];
    }
  }

  function viewCenter() {
    const rect = container?.getBoundingClientRect();
    if (!rect) return { x: 0, y: 0 };
    const p = flow.screenToFlowPosition({ x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 });
    return { x: p.x - 60 + (Math.random() - 0.5) * 40, y: p.y - 17 + (Math.random() - 0.5) * 40 };
  }

  function ondragover(e: DragEvent) {
    if (e.dataTransfer?.types.includes("application/x-reticulum-device")) {
      e.preventDefault();
      e.dataTransfer.dropEffect = "copy";
    }
  }

  function ondrop(e: DragEvent) {
    const key = e.dataTransfer?.getData("application/x-reticulum-device");
    const device = key ? deviceAt(key) : undefined;
    if (!device) return;
    e.preventDefault();
    if (copy) {
      fillSlot(e, device);
      return;
    }
    const p = flow.screenToFlowPosition({ x: e.clientX, y: e.clientY });
    // Dropped in a frame: onto the sheet open there (the innermost frame under the pointer).
    let into: { sheet: Sheet; x: number; y: number; area: number } | null = null;
    for (const [frameId, frame] of view.frames) {
      const internal = flow.getInternalNode(frameId);
      const box = internal?.internals.positionAbsolute;
      const width = internal?.measured.width ?? 0;
      const height = internal?.measured.height ?? 0;
      const held = app.snapshot?.sheets.find((x) => x.id === frame.sheet);
      if (!box || !held || p.x < box.x || p.y < box.y || p.x > box.x + width || p.y > box.y + height) continue;
      if (!into || width * height < into.area) into = { sheet: held, x: p.x - box.x + frame.dx, y: p.y - box.y + frame.dy, area: width * height };
    }
    if (into) addNode("device", { x: into.x - 70, y: into.y - 17 }, device, into.sheet);
    else addNode("device", { x: p.x - 70, y: p.y - 17 }, device);
  }

  /** A device dropped on a slot of the open copy fills it (turned like the copy's other slots). */
  function fillSlot(e: DragEvent, device: Device) {
    if (!copy || !canEdit) return;
    const id = document.elementFromPoint(e.clientX, e.clientY)?.closest(".svelte-flow__node")?.getAttribute("data-id");
    const slot = sheet?.nodes?.find((n) => n.id === id && n.settings.kind === "device");
    if (!slot) {
      toast("Drop it on one of the slots (the dashed cards).", "info");
      return;
    }
    const bindings = copy.node.settings.bindings ?? {};
    const turns = Object.values(bindings).find((b) => b.turns)?.turns;
    setBindings(copy.sheet, copy.node, { ...bindings, [slot.id]: { device: device.pos, ...(turns ? { turns } : {}) } }, `fill ${slotName(slot)}`);
    app.selectedNodes = [slot.id];
  }

  /** Other networks a Network node here could switch: ones this player admins. */
  const switchable = $derived(app.networks.filter((n) => n.id !== app.current && (n.role === "admin" || n.role === "owner")));

  /** Blueprints that can be placed here (as copies): not this sheet. */
  const blueprints = $derived((app.snapshot?.sheets ?? []).filter((x) => x.blueprint && x.id !== sheet?.id));
  const palette = $derived<NodeKind[]>([
    "device",
    "filter",
    "rate_limit",
    "splitter",
    "kit",
    // Sheets go inside sheets in the tree, never inside a blueprint.
    ...(sheet?.blueprint ? [] : (["sheet"] as NodeKind[])),
    ...(blueprints.length ? (["copy"] as NodeKind[]) : []),
    // Other networks to switch: only from the tree, never from a blueprint (each copy would switch the same one).
    ...(sheet?.blueprint ? [] : (["network"] as NodeKind[])),
    // The root has no edge to pin: nothing is outside it.
    ...(sheet && !sheet.root ? (["sheet_input", "sheet_output"] as NodeKind[]) : []),
  ]);

  /**
   * Double-click a sheet node to open its sheet, or a copy node to open the copy (its blueprint with its blocks); a
   * copy inside a blueprint opens that blueprint.
   */
  function nodeClick({ node, event }: { node: Node<NodeData>; event: MouseEvent | TouchEvent }) {
    const s = node.data.node.settings;
    if (!(event instanceof MouseEvent) || event.detail !== 2 || (s.kind !== "copy" && s.kind !== "sheet")) return;
    if (!app.snapshot?.sheets.some((x) => x.id === s.sheet)) return;
    const on = sheetOf(node.id);
    if (s.kind === "copy" && on && !on.blueprint) openCopy(on.id, node.id);
    else openSheet(s.sheet);
  }

  let tidying = $state(false);

  /** Lays the sheet out left to right with ELK, as one undoable move. */
  async function tidy() {
    if (!sheet || tidying) return;
    tidying = true;
    try {
      const sizes = new Map(nodes.map((n) => [n.id, { width: n.measured?.width ?? 160, height: n.measured?.height ?? 60 }]));
      // Each pin's center on its card, as Svelte Flow measured it.
      const pins: PinPositions = new Map();
      for (const n of nodes) {
        const bounds = flow.getInternalNode(n.id)?.internals.handleBounds;
        const byHandle = new Map<string, { x: number; y: number }>();
        for (const h of [...(bounds?.source ?? []), ...(bounds?.target ?? [])]) {
          if (h.id) byHandle.set(h.id, { x: h.x + h.width / 2, y: h.y + h.height / 2 });
        }
        pins.set(n.id, byHandle);
      }
      const laidOut = sheet;
      const positions = await layout(laidOut, sizes, pins);
      // Laying out takes a moment; if the sheet changed meanwhile, these positions are for the one laid out.
      if (currentSheet()?.id !== laidOut.id) return;
      const before = (laidOut.nodes ?? []).map((n) => ({ id: n.id, x: n.x ?? 0, y: n.y ?? 0 }));
      await edit([{ type: "moveNodes", sheet: laidOut.id, positions }], [{ type: "moveNodes", sheet: laidOut.id, positions: before }], "tidy up");
      setTimeout(() => flow.fitView({ duration: 300 }), 100);
    } catch (e) {
      toast(`Couldn't tidy up: ${(e as Error).message}`);
    } finally {
      tidying = false;
    }
  }
</script>

<!-- svelte-ignore a11y_no_static_element_interactions -->
<!-- "wiring" while a new wire is being dragged, so pins that hide when unused (gates) show as drop targets. -->
<div class="h-full w-full" class:wiring bind:this={container} {ondragover} {ondrop}>
  <SvelteFlow
    bind:nodes
    bind:edges
    connectionLineType={app.wireStyle as ConnectionLineType}
    {nodeTypes}
    fitView
    minZoom={0.2}
    maxZoom={2}
    deleteKey={editable && modals.open === 0 ? ["Delete", "Backspace"] : null}
    nodesDraggable={editable}
    nodesConnectable={editable}
    elementsSelectable={true}
    onselectionchange={selectionChange}
    onconnect={connect}
    onconnectstart={() => (wiring = true)}
    onconnectend={() => (wiring = false)}
    isValidConnection={validConnection}
    ondelete={remove}
    onnodedragstart={() => {
      dragging = true;
      holdMetrics(true);
    }}
    onnodedragstop={dragStop}
    onnodeclick={nodeClick}
    proOptions={{ hideAttribution: true }}
  >
    <Background gap={24} />
    <Controls showLock={false} />
    <MiniMap pannable zoomable maskStrokeWidth={1} nodeClass={(n) => (n.data?.problem ? "problem" : "")} />
    {#if editable}
      <Panel position="top-left">
        <div class="flex flex-wrap gap-1 rounded-lg border border-line bg-panel p-1">
          {#each palette as kind}
            {@const Icon = KIND_ICON[kind]}
            <button class="btn flex items-center gap-1 text-sm" title={KIND_HINT[kind]} onclick={() => addNode(kind, viewCenter())}>
              <Icon size={14} />{KIND_LABEL[kind]}
            </button>
          {/each}
          <MenuButton
            label="Redstone"
            icon={ZapIcon}
            color="var(--c-signal)"
            items={SIGNAL_KINDS.map((kind) => ({ key: kind, label: KIND_LABEL[kind], hint: KIND_HINT[kind], icon: KIND_ICON[kind] }))}
            onpick={(kind) => addNode(kind as NodeKind, viewCenter())}
          />
          <!-- Wire style: a canvas setting, remembered in this browser. -->
          <MenuButton
            label={WIRE_STYLE_LABEL[app.wireStyle]}
            icon={SplineIcon}
            width={140}
            title="How wires are drawn between pins (remembered in this browser)"
            items={(Object.keys(WIRE_STYLE_LABEL) as WireStyle[]).map((style) => ({
              key: style,
              label: WIRE_STYLE_LABEL[style],
              checked: style === app.wireStyle,
            }))}
            onpick={(style) => setWireStyle(style as WireStyle)}
          />
          <button class="btn flex items-center gap-1 text-sm" title="Lay the sheet out left to right" disabled={tidying || !sheet?.nodes?.length} onclick={tidy}>
            <SparklesIcon size={14} />Tidy up
          </button>
        </div>
      </Panel>
    {/if}
    {#if sheet && app.snapshot && (!sheet.root || sheet.blueprint)}
      <Panel position="top-right">
        <Breadcrumbs {sheet} snapshot={app.snapshot} copy={copy ? { sheet: copy.sheet, node: copy.node } : null} />
      </Panel>
    {/if}
    {#if sheet && app.snapshot}
      <Panel position="bottom-center">
        <div class="flex max-w-xl flex-col gap-2">
          {#if copy}
            <p class="box text-sm" role="status">
              A copy of the blueprint “{copy.blueprint.name}”: drop devices from the list on its slots to fill them. To change
              anything else, edit the blueprint{copyCount > 1 ? ` (that changes all ${copyCount} copies)` : ""}.
              <button class="btn ml-1 text-xs" onclick={() => openSheet(copy.blueprint.id)}>Edit the blueprint</button>
            </p>
          {:else if sheet.blueprint}
            <p class="box text-sm" role="status">
              A blueprint: it runs only as its copies. Devices dropped here become slots for their kind of block.
              {copyCount ? `Changes here reach its ${copyCount === 1 ? "copy" : `${copyCount} copies`}.` : "It has no copies yet."}
            </p>
          {/if}
          <SheetOffNotice {sheet} snapshot={app.snapshot} />
          <SheetErrors sheetId={sheet.id} errors={sheet.errors} overlay />
        </div>
      </Panel>
    {/if}
  </SvelteFlow>
</div>
