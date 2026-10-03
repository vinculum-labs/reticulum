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
    useConnection,
    ViewportPortal,
    type Edge,
    type ConnectionLineType,
    type Node,
    type Connection,
    type OnConnectEnd,
  } from "@xyflow/svelte";
  import {
    savedView,
    saveView,
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
    showNode,
    setBindings,
    toast,
    uuid,
    holdMetrics,
    deviceUses,
    deviceName,
    setWireStyle,
    WIRE_STYLE_LABEL,
    type WireStyle,
  } from "../../lib/state.svelte";
  import { copiesOf, slotName } from "../../lib/blueprints";
  import { confirmDialog } from "../../lib/dialog.svelte";
  import { heldSheet, descendants } from "../../lib/tree";
  import Breadcrumbs from "./Breadcrumbs.svelte";
  import { contentCorner, handleIndex, isPin, openFrameSize, pinResource, resources, PIN_DOT, PIN_W, toFlow, type FlowView, type NodeData } from "../../lib/graph";
  import { crossWire, nextOrder } from "../../lib/crossing";
  import { moveAcross } from "../../lib/moveAcross";
  import { KIND_HINT, KIND_LABEL, RESOURCE_VAR, SIGNAL_KINDS, defaultSettings, isSignal, offeredResources } from "../../lib/kinds";
  import type { Device, Edit, GraphNode, NodeKind, Resource, Sheet, Wire } from "../../lib/types";
  import { GATE, posKey } from "../../lib/types";
  import GraphNodeView from "./GraphNode.svelte";
  import ConnectionLine from "./ConnectionLine.svelte";
  import { pinDrop } from "../../lib/pinDrop.svelte";
  import EdgePin from "./EdgePin.svelte";
  import SheetFrame from "./SheetFrame.svelte";
  import { layout, type PinPositions } from "../../lib/layout";

  const nodeTypes = { graph: GraphNodeView, pin: EdgePin, sheetFrame: SheetFrame };
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

  /**
   * Each sheet opens where it was left (pan and zoom, remembered per browser), or fitted to its contents the first
   * time: once its cards are measured and Svelte Flow is ready. The canvas stays for every sheet (re-making Svelte Flow
   * per sheet left the handle here pointing at a dead store), so this runs whenever the open sheet (or copy) changes.
   */
  const viewKey = $derived(copy ? `copy:${copy.node.id}` : (sheet?.id ?? null));
  let viewShown: string | null = null;
  /** The sheet (or copy) the canvas's nodes are for: right after a switch they are still the last one's. */
  let builtFor: string | null = null;
  let flowReady = $state(false);
  $effect(() => {
    const key = viewKey;
    const list = nodes;
    if (!flowReady || !key || key === viewShown || !container) return;
    const width = container.clientWidth;
    const height = container.clientHeight;
    // Only once the canvas holds this sheet's nodes, all measured.
    if (!width || !height || builtFor !== key) return;
    if (list.some((n) => n.type === "graph" && !n.measured?.width)) return;
    untrack(() => {
      viewShown = key;
      // A node asked for (from the dashboard, an error) is brought into view instead.
      if (app.focusNode) return;
      const saved = savedView(key);
      if (saved) {
        flow.setViewport(saved);
        return;
      }
      // The box around what is on the sheet itself (frames hold their own): cards as measured, the frame by its data.
      let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
      for (const n of list) {
        if (n.parentId) continue;
        const size = n.type === "sheetFrame" ? (n.data as unknown as { width: number; height: number }) : { width: n.measured?.width ?? 0, height: n.measured?.height ?? 0 };
        minX = Math.min(minX, n.position.x);
        minY = Math.min(minY, n.position.y);
        maxX = Math.max(maxX, n.position.x + size.width);
        maxY = Math.max(maxY, n.position.y + size.height);
      }
      if (!(maxX > minX) || !(maxY > minY)) {
        flow.setViewport({ x: 0, y: 0, zoom: 1 });
        return;
      }
      const zoom = Math.min(1, width / ((maxX - minX) * 1.1), height / ((maxY - minY) * 1.1));
      flow.setViewport({ x: width / 2 - ((minX + maxX) / 2) * zoom, y: height / 2 - ((minY + maxY) / 2) * zoom, zoom });
    });
  });

  function moveEnd(_event: unknown, viewport: { x: number; y: number; zoom: number }) {
    if (viewKey && viewKey === viewShown) saveView(viewKey, viewport);
  }

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
    for (const n of untrack(() => nodes)) {
      if (n.type === "graph" && n.measured?.width && !n.data.frame) sizes.set(n.id, { width: n.measured.width, height: n.measured.height ?? 0 });
    }
    const bindings = copy ? (copy.node.settings.bindings ?? {}) : undefined;
    const built = toFlow(s, app.snapshot?.sheets ?? [], devices, activity, selected, unit, app.wireStyle, open, sizes, bindings);
    view = built;
    builtFor = untrack(() => viewKey);
    sizedWith = sizes;
    // Alt still held: new cards in frames don't grow them either.
    if (altDown) for (const n of built.nodes) if (n.parentId && n.type === "graph") n.expandParent = false;
    // The open sheet's frame offers its + input / + output buttons only where the sheet can be edited.
    for (const n of built.nodes) if (n.type === "sheetFrame") (n.data as unknown as { canAdd: boolean }).canAdd = editable;
    untrack(() => {
      if (app.selectedNodes.some((id) => !built.owner.has(id))) app.selectedNodes = app.selectedNodes.filter((id) => built.owner.has(id));
      if (app.selectedEdges.some((id) => !built.owner.has(id))) app.selectedEdges = app.selectedEdges.filter((id) => built.owner.has(id));
    });
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

  // Cards in a frame (or the open sheet's) measured at another size than it was sized for: size it again (settles in
  // a pass or two).
  $effect(() => {
    const list = nodes;
    if (!view.sheetFrame && !list.some((n) => n.parentId)) return;
    const off = list.some((n) => {
      if (n.type !== "graph" || n.data.frame || !n.measured?.width) return false;
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
    if (a.width !== b.width || a.height !== b.height) return false;
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
    // A frame's own handles are for wires outside it; inside, wire to the pins on its edges.
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
    const wire: Wire = { id: uuid(), from: c.source, output, to: c.target, input, order: nextOrder(from, c.source, output) };
    // An untyped sheet pin takes the type of what it is first wired to.
    const edits: Edit[] = [];
    const undo: Edit[] = [{ type: "disconnect", sheet: from.id, wire: wire.id }];
    const fromNode = nodeOf(c.source);
    const toNode = nodeOf(c.target);
    const typePin = (pin: GraphNode | undefined, carried: Resource | null | undefined) => {
      const s = pin?.settings;
      if (!pin || !carried || (s?.kind !== "sheet_input" && s?.kind !== "sheet_output") || s.resource) return;
      edits.push({ type: "updateNode", sheet: from.id, node: { ...pin, settings: { ...s, resource: carried } } });
      undo.push({ type: "updateNode", sheet: from.id, node: pin });
    };
    if (fromNode?.settings.kind === "sheet_input" && toNode) {
      typePin(fromNode, input === GATE || isSignal(toNode.settings.kind) ? "redstone" : pinResource(toNode, "in", input, app.snapshot.sheets));
    }
    if (toNode?.settings.kind === "sheet_output" && fromNode) {
      typePin(toNode, isSignal(fromNode.settings.kind) ? "redstone" : (pinResource(fromNode, "out", output, app.snapshot.sheets) ?? resources(from, app.snapshot.sheets).nodes.get(fromNode.id)));
    }
    await edit([...edits, { type: "connect", sheet: from.id, wire }], undo, "connect");
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
    // Restore every wire that touched a removed node, plus wires cut on their own; not those on a deleted sheet's
    // node, which doesn't come back.
    const heldNodes = new Set(gone.filter((n) => heldSheet(app.snapshot, nodeOf(n.id))).map((n) => n.id));
    for (const on of touchedSheets.values()) {
      for (const w of on.wires ?? []) {
        if (heldNodes.has(w.from) || heldNodes.has(w.to)) {
          if (cut.some((e) => e.id === w.id)) edits.push({ type: "disconnect", sheet: on.id, wire: w.id });
          continue;
        }
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

  async function dragStop({ nodes: moved, event }: { nodes: Node[]; event: MouseEvent | TouchEvent }) {
    dragging = false;
    if (!sheet || moved.length === 0) {
      holdMetrics(false);
      return;
    }
    if (await moveToOtherSheet(moved, event)) {
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
   * Cards dropped on another sheet than their own move there: into a frame under the pointer, or out of theirs (with
   * Alt held, so it didn't grow under them) onto the sheet around it. Their wires that now cross an edge are rebuilt
   * through pins, as one change; refused when one can't be. Sheets and pins don't move this way. Returns whether the
   * drop was handled here (moved or refused).
   */
  async function moveToOtherSheet(moved: Node[], event: MouseEvent | TouchEvent): Promise<boolean> {
    if (!sheet || !app.snapshot || !editable) return false;
    const cards = moved.filter((n) => n.type === "graph");
    const owners = new Set(cards.map((n) => view.owner.get(n.id)));
    if (!cards.length || owners.size !== 1) return false;
    const point = "changedTouches" in event ? event.changedTouches[0] : event;
    if (!point) return false;
    const p = flow.screenToFlowPosition({ x: point.clientX, y: point.clientY });
    // The innermost frame under the pointer, not one being dragged (or inside one being dragged).
    const dragged = new Set(moved.map((n) => n.id));
    let target: { sheet: Sheet; frame?: (typeof view.frames extends Map<string, infer F> ? F : never); area: number } = { sheet, area: Infinity };
    for (const [frameId, frame] of view.frames) {
      if (dragged.has(frameId) || framesAround(frameId).some((id) => dragged.has(id))) continue;
      const internal = flow.getInternalNode(frameId);
      const at = internal?.internals.positionAbsolute;
      const width = internal?.measured.width ?? 0;
      const height = internal?.measured.height ?? 0;
      const held = app.snapshot.sheets.find((x) => x.id === frame.sheet);
      if (!at || !held || p.x < at.x || p.y < at.y || p.x > at.x + width || p.y > at.y + height) continue;
      if (width * height < target.area) target = { sheet: held, frame, area: width * height };
    }
    // Onto a closed sheet node (no frame): into its sheet, to the right of what is already there.
    let intoClosed: { x: number; y: number } | null = null;
    for (const n of nodes) {
      const s = n.data?.node?.settings;
      if (n.type !== "graph" || s?.kind !== "sheet" || n.data.frame || dragged.has(n.id)) continue;
      const internal = flow.getInternalNode(n.id);
      const at = internal?.internals.positionAbsolute;
      const width = internal?.measured.width ?? 0;
      const height = internal?.measured.height ?? 0;
      if (!at || p.x < at.x || p.y < at.y || p.x > at.x + width || p.y > at.y + height) continue;
      const held = app.snapshot.sheets.find((x) => x.id === s.sheet);
      if (!held) continue;
      const cardsThere = (held.nodes ?? []).filter((x) => !isPin(x));
      intoClosed = {
        x: cardsThere.length ? Math.max(...cardsThere.map((x) => x.x ?? 0)) + 260 : 0,
        y: cardsThere.length ? Math.min(...cardsThere.map((x) => x.y ?? 0)) : 0,
      };
      target = { sheet: held, area: 0 };
    }
    const from = app.snapshot.sheets.find((x) => x.id === [...owners][0]);
    // Out past the open sheet's own frame (Alt held, so it didn't grow): up into the sheet around this one.
    let intoParent: { x: number; y: number } | null = null;
    const box = view.sheetFrame;
    const parent = sheet.parent ? app.snapshot.sheets.find((x) => x.id === sheet.parent) : undefined;
    if (target.sheet === sheet && event.altKey && box && parent && sheet.node
        && (p.x < box.x || p.y < box.y || p.x > box.x + box.width || p.y > box.y + box.height)) {
      const holder = parent.nodes?.find((n) => n.id === sheet.node);
      target = { sheet: parent, area: Infinity };
      // Beside this sheet's node there, on the side it was dropped.
      intoParent = { x: (holder?.x ?? 0) + (p.x < box.x ? -260 : 260), y: holder?.y ?? 0 };
    }
    if (!from || from.id === target.sheet.id) return false;
    const nodesMoved = cards.map((n) => nodeOf(n.id)).filter((n): n is GraphNode => !!n);
    if (nodesMoved.some((n) => n.settings.kind === "sheet" || isPin(n))) {
      toast("Sheets move with the list (or Move into in their settings), and pins stay on their sheet's edge.", "info");
      resync.count++;
      return true;
    }
    // Where each lands in the other sheet: from where it is on the canvas.
    const first = flow.getInternalNode(nodesMoved[0].id)?.internals.positionAbsolute ?? { x: 0, y: 0 };
    const items = nodesMoved.map((node) => {
      const abs = flow.getInternalNode(node.id)?.internals.positionAbsolute ?? { x: node.x ?? 0, y: node.y ?? 0 };
      // Into a sheet not on the canvas (around this one, or a closed sheet node's): keeping their spacing.
      const spot = intoParent ?? intoClosed;
      if (spot) return { node, x: spot.x + abs.x - first.x, y: spot.y + abs.y - first.y };
      const f = target.frame;
      return { node, x: f ? abs.x - f.ax + f.dx : abs.x, y: f ? abs.y - f.ay + f.dy : abs.y };
    });
    const change = moveAcross(app.snapshot, from, target.sheet, items, nodeTitle, uuid);
    if (!change) {
      toast("Can't move it there: one of its wires couldn't be kept.");
      resync.count++;
      return true;
    }
    const ok = await edit(change.edits, change.undo, nodesMoved.length === 1 ? `move to ${target.sheet.name}` : `move ${nodesMoved.length} to ${target.sheet.name}`);
    // Off the canvas now: a button there takes you to it.
    const goTo = { label: "Show", run: () => showNode(target.sheet.id, nodesMoved[0].id) };
    if (ok && intoParent) toast(`Moved to “${target.sheet.name}”, beside this sheet's node (undo puts it back).`, "info", goTo);
    if (ok && intoClosed) toast(`Moved into “${target.sheet.name}” (undo puts it back).`, "info", goTo);
    return true;
  }

  /**
   * Alt (⌥ Option) held: cards dragged in a frame don't grow it, so they can be dragged out of it. Svelte Flow reads
   * this when a drag starts, so Alt goes down first.
   */
  let altDown = false;
  function setAlt(on: boolean) {
    if (altDown === on) return;
    altDown = on;
    if (dragging) return;
    nodes = nodes.map((n) => (n.parentId && n.type === "graph" ? { ...n, expandParent: !on } : n));
  }
  $effect(() => {
    const down = (e: KeyboardEvent) => e.key === "Alt" && setAlt(true);
    const up = (e: KeyboardEvent) => e.key === "Alt" && setAlt(false);
    const lost = () => setAlt(false);
    window.addEventListener("keydown", down);
    window.addEventListener("keyup", up);
    window.addEventListener("blur", lost);
    return () => {
      window.removeEventListener("keydown", down);
      window.removeEventListener("keyup", up);
      window.removeEventListener("blur", lost);
    };
  });

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
      const node = nodeOf(n.id);
      if (!on || !node) continue;
      const frame = n.parentId ? view.frames.get(n.parentId) : undefined;
      // In a frame: from where it is on the canvas, as the frame may have grown (and moved) while dragging.
      const abs = frame ? (flow.getInternalNode(n.id)?.internals.positionAbsolute ?? n.position) : n.position;
      const x = frame ? abs.x - frame.ax + frame.dx : abs.x;
      const y = frame ? abs.y - frame.ay + frame.dy : abs.y;
      // A pin sits on the edge: only its height moves (its y is its middle).
      if (isPin(node)) put(on.id, n.id, node.x ?? 0, y + PIN_DOT);
      else put(on.id, n.id, x, y);
    }
    // Frames whose contents moved, innermost first: moving a frame can shift the one around it in turn.
    const settled = new Set<string>();
    for (let pass = 0; pass < 8; pass++) {
      let shifted = false;
      // Frames are listed outside in; the innermost go first.
      for (const [frameId, frame] of [...view.frames].reverse()) {
        const inside = placed.get(frame.sheet);
        const held = app.snapshot?.sheets.find((x) => x.id === frame.sheet);
        const outer = sheetOf(frameId);
        const node = nodeOf(frameId);
        if (!inside || !held?.nodes?.length || !outer || !node || settled.has(frameId)) continue;
        settled.add(frameId);
        const spot = (n: GraphNode) => inside.get(n.id) ?? { x: n.x ?? 0, y: n.y ?? 0 };
        const now = contentCorner(held.nodes, spot);
        const was = contentCorner(held.nodes);
        const dx = now.x - was.x;
        const dy = now.y - was.y;
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

  /**
   * While dragging: frames grow under the dragged cards (Svelte Flow's expandParent for open sheet nodes, here for
   * the open sheet's own frame), and their pins stay on their edges as they do.
   */
  function keepPinsOnEdges() {
    const frameOf = new Map(nodes.filter((n) => n.data?.frame).map((n) => [n.id, n]));
    let backdrop = nodes.find((n) => n.type === "sheetFrame");
    // With Alt held, the open sheet's frame doesn't grow either: cards dropped outside it leave the sheet.
    if (backdrop && view.sheetFrame && !altDown) {
      // The open sheet's frame: as big as it was built, or more to take in the cards dragged past it.
      let { x, y, width, height } = view.sheetFrame;
      for (const n of nodes) {
        if (n.parentId || n.type !== "graph" || !n.dragging) continue;
        const w = n.measured?.width ?? 0;
        const h = n.measured?.height ?? 0;
        const left = Math.min(x, n.position.x - 24);
        const top = Math.min(y, n.position.y - 24);
        width = Math.max(x + width, n.position.x + w + 24) - left;
        height = Math.max(y + height, n.position.y + h + 24) - top;
        x = left;
        y = top;
      }
      const data = backdrop.data as unknown as { width: number; height: number };
      if (backdrop.position.x !== x || backdrop.position.y !== y || data.width !== width || data.height !== height) {
        backdrop = { ...backdrop, position: { x, y }, data: { ...backdrop.data, width, height } };
      } else {
        backdrop = undefined;
      }
    } else {
      backdrop = undefined;
    }
    const box = backdrop ? { x: backdrop.position.x, width: (backdrop.data as unknown as { width: number }).width } : null;
    let changed = !!backdrop;
    const next = nodes.map((n) => {
      if (backdrop && n.type === "sheetFrame") return backdrop;
      if (n.type !== "pin") return n;
      const input = n.data.node.settings.kind === "sheet_input";
      let want: number | undefined;
      if (n.parentId) {
        const frame = frameOf.get(n.parentId);
        const width = frame?.width ?? frame?.measured?.width;
        if (width) want = input ? -PIN_W : width;
      } else if (box) {
        want = input ? box.x - PIN_W : box.x + box.width;
      }
      if (want === undefined || n.position.x === want) return n;
      changed = true;
      return { ...n, position: { ...n.position, x: want } };
    });
    if (changed) nodes = next;
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
    if (needsDevice && !device) {
      device = toolbarDevice(kind, into);
      if (!device) return;
    }
    if (into.blueprint && kind === "device" && device) {
      // A device on a blueprint (dropped, or the toolbar's: the selected one) makes a slot for its kind of block (it isn't tied to that block).
      const node: GraphNode = { id: uuid(), settings: { ...defaultSettings(kind, device, offered(device)), block: device.block } as GraphNode["settings"], x: Math.round(at.x), y: Math.round(at.y) };
      const ok = await edit([{ type: "addNode", sheet: into.id, node }], [{ type: "removeNode", sheet: into.id, node: node.id }], "add slot");
      if (ok) app.selectedNodes = [node.id];
      return;
    }
    const blueprint = kind === "copy" ? blueprints[0]?.id : undefined;
    if (kind === "copy" && !blueprint) {
      toast("Make a blueprint first: from a sheet (in its settings), or New blueprint in the list.");
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

  /**
   * The device the toolbar's Device (or Level sensor) uses: the one selected in the list, else the first one. A device
   * node on a sheet of the tree is its block's one home, so it takes one that has none yet; a slot or a sensor can
   * name any. Says why when there is none.
   */
  function toolbarDevice(kind: NodeKind, into: Sheet): Device | null {
    const devices = (app.snapshot?.devices ?? []).filter((d) => d.state === "active");
    if (!devices.length) {
      toast("There are no devices on this network yet.");
      return null;
    }
    const selected = app.selectedDevice ? deviceAt(app.selectedDevice) : undefined;
    if (kind !== "device" || into.blueprint) return selected ?? devices[0];
    const uses = deviceUses(app.snapshot!);
    const free = (d: Device) => !(uses.get(posKey(d.pos)) ?? []).some((u) => u.home);
    const pick = selected && free(selected) ? selected : devices.find(free);
    if (!pick) toast("Every device already has a node (each lives on one sheet). Move its node, or wire to it through pins.");
    else if (selected && pick !== selected) toast(`${deviceName(selected)} already has a node, so this one is ${deviceName(pick)}.`);
    return pick ?? null;
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
    setBindings(
      copy.sheet,
      copy.node,
      (bindings) => {
        const turns = Object.values(bindings).find((b) => b.turns)?.turns;
        return { ...bindings, [slot.id]: { device: device.pos, ...(turns ? { turns } : {}) } };
      },
      `fill ${slotName(slot)}`,
    );
    app.selectedNodes = [slot.id];
  }

  /**
   * A wire dragged from a node past the edge of the frame it sits in (and dropped on nothing) makes a pin there: an
   * output pin past the right edge, an input pin past the left, named and typed after the node, as one change.
   */
  /**
   * Where a wire dragged from a node would make a pin if dropped at {@code p} (canvas coordinates): past the right
   * edge of the frame the node sits in for an output, past the left for an input. Null anywhere else. The preview
   * while dragging and the drop use this alike.
   */
  function edgePinAt(fromId: string, handle: { id?: string | null; type: string }, p: { x: number; y: number }) {
    if (!editable || !app.snapshot) return null;
    const on = sheetOf(fromId);
    const fromNode = nodeOf(fromId);
    if (!on || on.root || !fromNode || isPin(fromNode)) return null;
    // The frame the node sits in: the open sheet's, or an open sheet node's.
    let box: { x: number; y: number; width: number; height: number } | undefined;
    let dy = 0;
    if (on.id === sheet?.id) {
      box = view.sheetFrame;
    } else {
      const entry = [...view.frames].find(([, f]) => f.sheet === on.id);
      const internal = entry && flow.getInternalNode(entry[0]);
      if (entry && internal) {
        box = { ...internal.internals.positionAbsolute, width: internal.measured.width ?? 0, height: internal.measured.height ?? 0 };
        dy = entry[1].dy;
      }
    }
    if (!box) return null;
    const output = handle.type === "source";
    if (output ? p.x < box.x + box.width : p.x > box.x) return null;
    const index = handleIndex(handle.id);
    const carried =
      pinResource(fromNode, output ? "out" : "in", index, app.snapshot.sheets) ??
      (isSignal(fromNode.settings.kind) || handle.id === "gate" ? "redstone" : undefined);
    return {
      on,
      fromNode,
      output,
      index,
      carried,
      // On the canvas: where the pin's dot would sit on the edge.
      edge: { x: output ? box.x + box.width : box.x, y: p.y },
      // In the sheet: the pin's y.
      y: Math.round(on.id === sheet?.id ? p.y : p.y - box.y + dy),
    };
  }

  /** The pin a wire being dragged would make if dropped where the pointer is now (none over a pin or inside). */
  const connection = useConnection();
  const pinPreview = $derived.by(() => {
    const c = connection.current;
    if (!c.inProgress || c.toHandle || !c.fromNode || !c.fromHandle) return null;
    const drop = edgePinAt(c.fromNode.id, c.fromHandle, c.to);
    return drop && { ...drop, color: drop.carried ? RESOURCE_VAR[drop.carried] : "var(--muted)" };
  });

  // The dragged wire is drawn only as far as that pin's dot.
  $effect(() => {
    const preview = pinPreview;
    pinDrop.at = preview ? { ...preview.edge, output: preview.output } : null;
  });

  async function connectEnd(event: MouseEvent | TouchEvent, state: Parameters<OnConnectEnd>[1]) {
    wiring = false;
    if (!state.fromNode || !state.fromHandle || state.toNode) return;
    const point = "changedTouches" in event ? event.changedTouches[0] : event;
    const drop = edgePinAt(state.fromNode.id, state.fromHandle, flow.screenToFlowPosition({ x: point.clientX, y: point.clientY }));
    if (!drop) return;
    const { on, fromNode, output, index, carried } = drop;
    const pin: GraphNode = {
      id: uuid(),
      settings: { kind: output ? "sheet_output" : "sheet_input", name: nodeTitle(fromNode), ...(carried ? { resource: carried } : {}) },
      x: 0,
      y: drop.y,
    };
    const wire: Wire = output
      ? { id: uuid(), from: fromNode.id, output: index, to: pin.id, input: 0, order: nextOrder(on, fromNode.id, index) }
      : { id: uuid(), from: pin.id, output: 0, to: fromNode.id, input: index, order: 0 };
    await edit(
      [{ type: "addNode", sheet: on.id, node: pin }, { type: "connect", sheet: on.id, wire }],
      [{ type: "disconnect", sheet: on.id, wire: wire.id }, { type: "removeNode", sheet: on.id, node: pin.id }],
      output ? "add output pin" : "add input pin",
    );
  }

  /** Blueprints that can be placed here (as copies): not this sheet. */
  const blueprints = $derived((app.snapshot?.sheets ?? []).filter((x) => x.blueprint && x.id !== sheet?.id));
  /** The toolbar's groups: nodes that move things (with the Redstone menu), then the ones that hold other nodes. */
  const movers: NodeKind[] = ["device", "filter", "rate_limit", "splitter", "kit"];
  const holders = $derived<NodeKind[]>([
    // Sheets go inside sheets in the tree, never inside a blueprint.
    ...(sheet?.blueprint ? [] : (["sheet"] as NodeKind[])),
    ...(blueprints.length ? (["copy"] as NodeKind[]) : []),
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
  /**
   * Lays the sheet out left to right with ELK, as one undoable move: the sheets open in frames on it first, innermost
   * first, then each sheet around them with its frames at their new sizes.
   */
  async function tidy() {
    if (!sheet || tidying || !app.snapshot) return;
    tidying = true;
    try {
      const sizes = new Map(nodes.map((n) => [n.id, { width: n.measured?.width ?? 160, height: n.measured?.height ?? 60 }]));
      // Each pin's center on its card, as Svelte Flow measured it (not an open frame's: those move with its insides).
      const pins: PinPositions = new Map();
      for (const n of nodes) {
        if (n.data?.frame) continue;
        const bounds = flow.getInternalNode(n.id)?.internals.handleBounds;
        const byHandle = new Map<string, { x: number; y: number }>();
        for (const h of [...(bounds?.source ?? []), ...(bounds?.target ?? [])]) {
          if (h.id) byHandle.set(h.id, { x: h.x + h.width / 2, y: h.y + h.height / 2 });
        }
        pins.set(n.id, byHandle);
      }
      const laidOut = sheet;
      const open = new Set(app.frames);
      const relaid = new Map<string, Sheet>();
      const sheetsNow = () => app.snapshot!.sheets.map((x) => relaid.get(x.id) ?? x);
      const edits: Edit[] = [];
      const undo: Edit[] = [];
      /** Lays one sheet out, its open frames sized from their (already laid out) insides; queues the move. */
      const tidySheet = async (target: Sheet, frames: [string, string][]) => {
        for (const [frameId, inner] of frames) {
          const held = relaid.get(inner);
          if (held) sizes.set(frameId, openFrameSize(held, sheetsNow(), open, sizes));
        }
        const positions = (await layout(target, sizes, pins)).map((p) => {
          // A pin's y is its middle (its x doesn't count: it sits on the edge).
          const node = target.nodes?.find((n) => n.id === p.id);
          return node && isPin(node) ? { ...p, y: p.y + PIN_DOT } : p;
        });
        relaid.set(target.id, { ...target, nodes: (target.nodes ?? []).map((n) => ({ ...n, ...(positions.find((p) => p.id === n.id) ?? {}) })) });
        edits.push({ type: "moveNodes", sheet: target.id, positions });
        undo.push({ type: "moveNodes", sheet: target.id, positions: (target.nodes ?? []).map((n) => ({ id: n.id, x: n.x ?? 0, y: n.y ?? 0 })) });
      };
      // view.frames lists frames outside in; inner sheets go first.
      const frames = [...view.frames];
      for (const [, frame] of [...frames].reverse()) {
        const held = app.snapshot.sheets.find((x) => x.id === frame.sheet);
        if (held) await tidySheet(held, frames.filter(([id]) => view.owner.get(id) === held.id).map(([id, f]) => [id, f.sheet]));
      }
      await tidySheet(laidOut, frames.filter(([id]) => view.owner.get(id) === laidOut.id).map(([id, f]) => [id, f.sheet]));
      // Laying out takes a moment; if the sheet changed meanwhile, these positions are for the one laid out.
      if (currentSheet()?.id !== laidOut.id) return;
      await edit(edits, undo, "tidy up");
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
    connectionLineComponent={ConnectionLine}
    {nodeTypes}
    minZoom={0.2}
    maxZoom={2}
    deleteKey={editable && modals.open === 0 ? ["Delete", "Backspace"] : null}
    nodesDraggable={editable}
    nodesConnectable={editable}
    elementsSelectable={true}
    onselectionchange={selectionChange}
    onconnect={connect}
    onconnectstart={() => (wiring = true)}
    onconnectend={connectEnd}
    isValidConnection={validConnection}
    ondelete={remove}
    onnodedragstart={() => {
      dragging = true;
      holdMetrics(true);
    }}
    onmoveend={moveEnd}
    oninit={() => (flowReady = true)}
    onnodedrag={keepPinsOnEdges}
    onnodedragstop={dragStop}
    onnodeclick={nodeClick}
    proOptions={{ hideAttribution: true }}
  >
    <Background gap={24} />
    {#if pinPreview}
      <!-- Where letting go makes a pin: a ghost of it on the frame's edge. -->
      <ViewportPortal target="front">
        <div
          class="pin-preview"
          class:output={pinPreview.output}
          style="left: {pinPreview.edge.x}px; top: {pinPreview.edge.y}px; --c: {pinPreview.color}"
        >
          <span class="dot"></span>
          <span class="tag">New {pinPreview.output ? "output" : "input"} pin</span>
        </div>
      </ViewportPortal>
    {/if}
    <Controls showLock={false} />
    <MiniMap pannable zoomable maskStrokeWidth={1} nodeClass={(n) => (n.data?.problem ? "problem" : "")} />
    {#if editable}
      <Panel position="top-left">
        <!-- Grouped by thin dividers, not colors (a color means what flows): nodes (Redstone too) | holders | the canvas. -->
        <div class="flex flex-wrap gap-1 rounded-lg border border-line bg-panel p-1">
          {#snippet addButton(kind: NodeKind)}
            {@const Icon = KIND_ICON[kind]}
            <button class="btn flex items-center gap-1 text-sm" title={KIND_HINT[kind]} onclick={() => addNode(kind, viewCenter())}>
              <Icon size={14} />{KIND_LABEL[kind]}
            </button>
          {/snippet}
          {#each movers as kind (kind)}{@render addButton(kind)}{/each}
          <MenuButton
            label="Redstone"
            icon={ZapIcon}
            color="var(--c-signal)"
            items={SIGNAL_KINDS.map((kind) => ({ key: kind, label: KIND_LABEL[kind], hint: KIND_HINT[kind], icon: KIND_ICON[kind] }))}
            onpick={(kind) => addNode(kind as NodeKind, viewCenter())}
          />
          <span class="toolbar-divider" aria-hidden="true"></span>
          {#if holders.length}
            {#each holders as kind (kind)}{@render addButton(kind)}{/each}
            <span class="toolbar-divider" aria-hidden="true"></span>
          {/if}
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

<style>
  /* The ghost of the pin a dropped wire would make: a dashed dot on the frame's edge, its tag outside it. */
  .pin-preview {
    position: absolute;
    display: flex;
    align-items: center;
    gap: 6px;
    transform: translate(-100%, -50%);
    flex-direction: row-reverse;
    pointer-events: none;
    font-size: 11px;
  }
  .pin-preview.output {
    transform: translate(0, -50%);
    flex-direction: row;
  }
  .pin-preview .dot {
    width: 12px;
    height: 12px;
    margin: 0 -6px;
    border-radius: 50%;
    border: 2px dashed var(--c);
    background: var(--panel);
  }
  .pin-preview .tag {
    padding: 1px 6px;
    border: 1px dashed var(--c);
    border-radius: 4px;
    background: var(--panel);
    white-space: nowrap;
  }
  .toolbar-divider {
    align-self: stretch;
    width: 1px;
    margin: 2px 2px;
    background: var(--line);
  }
</style>
