<script lang="ts">
  import { untrack } from "svelte";
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
  import { app, currentSheet, deviceAt, edit, toast, uuid, holdMetrics, setWireStyle, WIRE_STYLE_LABEL, type WireStyle } from "../../lib/state.svelte";
  import { handleIndex, pinResource, toFlow, type NodeData } from "../../lib/graph";
  import { KIND_HINT, KIND_LABEL, SIGNAL_KINDS, defaultSettings, isSignal, offeredResources } from "../../lib/kinds";
  import type { Device, Edit, GraphNode, NodeKind, Resource, Wire } from "../../lib/types";
  import GraphNodeView from "./GraphNode.svelte";
  import { layout, type PinPositions } from "../../lib/layout";

  const nodeTypes = { graph: GraphNodeView };
  const flow = useSvelteFlow();

  let nodes = $state.raw<Node<NodeData>[]>([]);
  let edges = $state.raw<Edge[]>([]);
  let dragging = false;
  let wiring = $state(false);
  let container: HTMLDivElement | undefined = $state();

  const sheet = $derived(currentSheet());
  const canEdit = $derived(app.snapshot?.canEdit ?? false);

  // Rebuild from the server's snapshot, and on every metrics update, except mid-drag.
  $effect(() => {
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
    if (!s || dragging) return;
    const built = toFlow(s, app.snapshot?.sheets ?? [], devices, activity, selected, unit, app.wireStyle);
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
    if (a.type !== b.type || !!a.selected !== !!b.selected || a.position.x !== b.position.x || a.position.y !== b.position.y) return false;
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

  function nodeOf(id: string): GraphNode | undefined {
    return sheet?.nodes?.find((n) => n.id === id);
  }

  /**
   * Signals go into signal inputs or a node's gate; resources go into resource inputs. Sheet pins carry either,
   * so they are left to the server to check.
   */
  function validConnection(c: Connection | Edge): boolean {
    const from = nodeOf(c.source);
    const to = nodeOf(c.target);
    if (!from || !to || c.source === c.target) return false;
    const pin = (k: string) => k === "sheet_input" || k === "sheet_output";
    // What each end carries: a device pin's resource, "signal" for signal nodes, "flow" for routing nodes.
    const fromPin = pinResource(from, "out", handleIndex(c.sourceHandle));
    const toPin = c.targetHandle === "gate" ? "signal" : pinResource(to, "in", handleIndex(c.targetHandle));
    const kindOf = (node: GraphNode, p: Resource | null | "signal") =>
      p === "redstone" || p === "signal" ? "signal" : p === "me" ? "me" : p ? "flow" : isSignal(node.settings.kind) ? "signal" : "flow";
    if (pin(from.settings.kind) || pin(to.settings.kind)) return true;
    return kindOf(from, fromPin) === kindOf(to, toPin);
  }

  async function connect(c: Connection) {
    if (!sheet) return;
    const output = handleIndex(c.sourceHandle);
    const input = handleIndex(c.targetHandle);
    const order = (sheet.wires ?? []).filter((w) => w.from === c.source && (w.output ?? 0) === output).length;
    const wire: Wire = { id: uuid(), from: c.source, output, to: c.target, input, order };
    await edit([{ type: "connect", sheet: sheet.id, wire }], [{ type: "disconnect", sheet: sheet.id, wire: wire.id }], "connect");
  }

  async function remove({ nodes: gone, edges: cut }: { nodes: Node[]; edges: Edge[] }) {
    if (!sheet) return;
    const goneIds = new Set(gone.map((n) => n.id));
    const edits: Edit[] = [];
    const undo: Edit[] = [];
    for (const n of gone) {
      const node = nodeOf(n.id);
      if (!node) continue;
      edits.push({ type: "removeNode", sheet: sheet.id, node: n.id });
      undo.push({ type: "addNode", sheet: sheet.id, node });
    }
    // Restore every wire that touched a removed node, plus wires cut on their own.
    for (const w of sheet.wires ?? []) {
      const touched = goneIds.has(w.from) || goneIds.has(w.to);
      const cutAlone = cut.some((e) => e.id === w.id) && !touched;
      if (cutAlone) edits.push({ type: "disconnect", sheet: sheet.id, wire: w.id });
      if (touched || cutAlone) undo.push({ type: "connect", sheet: sheet.id, wire: w });
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
    const positions = moved.map((n) => ({ id: n.id, x: Math.round(n.position.x), y: Math.round(n.position.y) }));
    const before = moved.map((n) => {
      const node = nodeOf(n.id);
      return { id: n.id, x: node?.x ?? 0, y: node?.y ?? 0 };
    });
    await edit([{ type: "moveNodes", sheet: sheet.id, positions }], [{ type: "moveNodes", sheet: sheet.id, positions: before }], "move");
    // Only now: held metrics applied before the move is saved would rebuild the canvas from the old positions, and
    // the node would jump back for a moment.
    holdMetrics(false);
  }

  function offered(device: Device): Resource {
    return offeredResources(device.resources)[0] ?? "item";
  }

  async function addNode(kind: NodeKind, at: { x: number; y: number }, device: Device | null = null) {
    if (!sheet) return;
    const needsDevice = kind === "device" || kind === "level";
    if (needsDevice && !device) {
      device = (app.selectedDevice && deviceAt(app.selectedDevice)) || app.snapshot?.devices.find((d) => d.state === "active") || null;
      if (!device) {
        toast("There are no devices on this network yet.");
        return;
      }
    }
    const subsheet = kind === "subsheet" ? subsheets[0]?.id : undefined;
    if (kind === "subsheet" && !subsheet) {
      toast("Mark another sheet as a sub-sheet first (in its settings).");
      return;
    }
    const node: GraphNode = {
      id: uuid(),
      settings: defaultSettings(kind, device, device ? offered(device) : "item", subsheet),
      x: Math.round(at.x),
      y: Math.round(at.y),
    };
    const ok = await edit([{ type: "addNode", sheet: sheet.id, node }], [{ type: "removeNode", sheet: sheet.id, node: node.id }], "add node");
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
    const p = flow.screenToFlowPosition({ x: e.clientX, y: e.clientY });
    addNode("device", { x: p.x - 70, y: p.y - 17 }, device);
  }

  /** Sheets that can be placed here as sub-sheets: marked as one, and not this sheet. */
  const subsheets = $derived((app.snapshot?.sheets ?? []).filter((x) => x.subsheet && x.id !== sheet?.id));
  const palette = $derived<NodeKind[]>([
    "device",
    "filter",
    "rate_limit",
    "splitter",
    "kit",
    ...(subsheets.length ? (["subsheet"] as NodeKind[]) : []),
    ...(sheet?.subsheet ? (["sheet_input", "sheet_output"] as NodeKind[]) : []),
  ]);

  /** Double-click a sub-sheet node to open the sheet it copies. */
  function nodeClick({ node, event }: { node: Node<NodeData>; event: MouseEvent | TouchEvent }) {
    const s = node.data.node.settings;
    if (event instanceof MouseEvent && event.detail === 2 && s.kind === "subsheet" && app.snapshot?.sheets.some((x) => x.id === s.sheet)) {
      app.sheetId = s.sheet;
      app.selectedNodes = [];
      app.selectedEdges = [];
    }
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
      const positions = await layout(sheet, sizes, pins);
      const before = (sheet.nodes ?? []).map((n) => ({ id: n.id, x: n.x ?? 0, y: n.y ?? 0 }));
      await edit([{ type: "moveNodes", sheet: sheet.id, positions }], [{ type: "moveNodes", sheet: sheet.id, positions: before }], "tidy up");
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
    deleteKey={canEdit ? ["Delete", "Backspace"] : null}
    nodesDraggable={canEdit}
    nodesConnectable={canEdit}
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
    <MiniMap pannable zoomable maskStrokeWidth={1} />
    {#if canEdit}
      <Panel position="top-left">
        <div class="flex flex-wrap gap-1 rounded-lg border p-1" style="background: var(--panel); border-color: var(--line)">
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
    {#if sheet?.errors?.length}
      <Panel position="bottom-center">
        <div class="max-w-xl rounded-lg border px-3 py-2 text-sm" style="background: var(--panel); border-color: var(--bad)">
          {#each sheet.errors as error}<div>{error}</div>{/each}
        </div>
      </Panel>
    {/if}
  </SvelteFlow>
</div>
