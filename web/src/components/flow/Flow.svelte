<script lang="ts">
  import {
    SvelteFlow,
    Background,
    Controls,
    MiniMap,
    Panel,
    useSvelteFlow,
    type Edge,
    type Node,
    type Connection,
  } from "@xyflow/svelte";
  import { app, currentSheet, deviceAt, edit, toast, uuid } from "../../lib/state.svelte";
  import { handleIndex, toFlow, type NodeData } from "../../lib/graph";
  import { KIND_HINT, KIND_LABEL, defaultSettings } from "../../lib/kinds";
  import type { Device, Edit, GraphNode, NodeKind, Resource, Wire } from "../../lib/types";
  import GraphNodeView from "./GraphNode.svelte";

  const nodeTypes = { graph: GraphNodeView };
  const flow = useSvelteFlow();

  let nodes = $state.raw<Node<NodeData>[]>([]);
  let edges = $state.raw<Edge[]>([]);
  let dragging = false;
  let container: HTMLDivElement | undefined = $state();

  const sheet = $derived(currentSheet());
  const canEdit = $derived(app.snapshot?.canEdit ?? false);

  // Rebuild from the server's snapshot, and on every metrics update, except mid-drag.
  $effect(() => {
    const s = sheet;
    const devices = app.snapshot?.devices ?? [];
    const activity = { rates: app.rates, fiveMinutes: app.fiveMinutes, ticksSinceMoved: app.ticksSinceMoved, status: app.status };
    const selected = new Set([...app.selectedNodes, ...app.selectedEdges]);
    const unit = app.rateUnit;
    if (!s || dragging) return;
    const built = toFlow(s, devices, activity, selected, unit);
    nodes = built.nodes;
    edges = built.edges;
  });

  function selectionChange({ nodes: n, edges: e }: { nodes: Node[]; edges: Edge[] }) {
    app.selectedNodes = n.map((x) => x.id);
    app.selectedEdges = e.map((x) => x.id);
    if (n.length || e.length) app.selectedDevice = null;
  }

  function nodeOf(id: string): GraphNode | undefined {
    return sheet?.nodes?.find((n) => n.id === id);
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
    if (!sheet || moved.length === 0) return;
    const positions = moved.map((n) => ({ id: n.id, x: Math.round(n.position.x), y: Math.round(n.position.y) }));
    const before = moved.map((n) => {
      const node = nodeOf(n.id);
      return { id: n.id, x: node?.x ?? 0, y: node?.y ?? 0 };
    });
    await edit([{ type: "moveNodes", sheet: sheet.id, positions }], [{ type: "moveNodes", sheet: sheet.id, positions: before }], "move");
  }

  function offered(device: Device): Resource {
    const r = device.resources ?? {};
    return r.item?.length ? "item" : r.fluid?.length ? "fluid" : r.energy?.length ? "energy" : "item";
  }

  async function addNode(kind: NodeKind, at: { x: number; y: number }, device: Device | null = null) {
    if (!sheet) return;
    if (kind === "device" && !device) {
      device = (app.selectedDevice && deviceAt(app.selectedDevice)) || app.snapshot?.devices.find((d) => d.state === "active") || null;
      if (!device) {
        toast("There are no devices on this network yet.");
        return;
      }
    }
    const node: GraphNode = {
      id: uuid(),
      settings: defaultSettings(kind, device, device ? offered(device) : "item"),
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

  const palette: NodeKind[] = ["device", "filter", "rate_limit", "splitter", "kit"];
</script>

<!-- svelte-ignore a11y_no_static_element_interactions -->
<div class="h-full w-full" bind:this={container} {ondragover} {ondrop}>
  <SvelteFlow
    bind:nodes
    bind:edges
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
    ondelete={remove}
    onnodedragstart={() => (dragging = true)}
    onnodedragstop={dragStop}
    proOptions={{ hideAttribution: true }}
  >
    <Background gap={24} />
    <Controls showLock={false} />
    <MiniMap pannable zoomable />
    {#if canEdit}
      <Panel position="top-left">
        <div class="flex flex-wrap gap-1 rounded-lg border p-1" style="background: var(--panel); border-color: var(--line)">
          {#each palette as kind}
            <button class="btn text-sm" title={KIND_HINT[kind]} onclick={() => addNode(kind, viewCenter())}>+ {KIND_LABEL[kind]}</button>
          {/each}
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
