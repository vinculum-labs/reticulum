<script lang="ts">
  import { inCopy } from "../lib/blueprints";
  import { app, currentSheet, deviceAt, openCopyNode, sheetHolding } from "../lib/state.svelte";
  import CopyPanel from "./inspector/CopyPanel.svelte";
  import SlotInspector from "./inspector/SlotInspector.svelte";
  import NodeInspector from "./inspector/NodeInspector.svelte";
  import WireInspector from "./inspector/WireInspector.svelte";
  import DeviceInspector from "./inspector/DeviceInspector.svelte";
  import SheetInspector from "./inspector/SheetInspector.svelte";
  import NetworkSettings from "./inspector/NetworkSettings.svelte";
  import IdentifiedPanel from "./inspector/IdentifiedPanel.svelte";
  import Dashboard from "./inspector/Dashboard.svelte";
  import EmptyState from "./EmptyState.svelte";

  const sheet = $derived(currentSheet());
  // What is selected may sit in a sheet opened in place (a frame on the canvas): it is inspected on its own sheet.
  const nodeSheet = $derived(app.selectedNodes.length === 1 ? sheetHolding(app.selectedNodes[0]) : undefined);
  const node = $derived(nodeSheet?.nodes?.find((n) => n.id === app.selectedNodes[0]));
  const wireSheet = $derived(!node && app.selectedEdges.length === 1 ? sheetHolding(app.selectedEdges[0]) : undefined);
  const wire = $derived(wireSheet?.wires?.find((w) => w.id === app.selectedEdges[0]));
  const device = $derived(app.selectedDevice ? deviceAt(app.selectedDevice) : undefined);
  /** A copy open on the canvas: only its slots can be changed, so the side panel is about them. */
  const copy = $derived(app.copy ? openCopyNode() : null);
</script>

<!-- A fixed width that nothing inside may widen: no sideways scrolling. -->
<aside class="flex w-80 shrink-0 flex-col overflow-x-hidden overflow-y-auto border-l border-line bg-panel p-3">
  {#if app.panel === "network" && app.snapshot}
    <NetworkSettings />
  {:else if app.panel === "identified" && app.snapshot}
    <IdentifiedPanel />
  {:else if app.panel === "dashboard" && app.snapshot}
    <Dashboard />
  {:else if device}
    {#key app.selectedDevice}
      <DeviceInspector {device} />
    {/key}
  {:else if copy}
    {#if node?.settings.kind === "device"}
      {#key node.id}
        <SlotInspector sheet={copy.sheet} copy={copy.node} blueprint={copy.blueprint} slot={node} />
      {/key}
    {:else if node}
      {#key node.id}
        <NodeInspector {node} sheet={copy.blueprint} inCopyAs={inCopy(copy.node.id, node.id)} />
      {/key}
    {:else}
      <CopyPanel sheet={copy.sheet} copy={copy.node} blueprint={copy.blueprint} />
    {/if}
  {:else if node && nodeSheet}
    {#key node.id}
      <NodeInspector {node} sheet={nodeSheet} />
    {/key}
  {:else if wire && wireSheet}
    <WireInspector {wire} sheet={wireSheet} />
  {:else if app.selectedNodes.length > 1}
    <EmptyState message={`${app.selectedNodes.length} nodes selected. Drag to move them together, or press Delete.`} />
  {:else if sheet}
    {#key sheet.id}
      <SheetInspector {sheet} />
    {/key}
  {:else}
    <EmptyState message="Nothing selected." />
  {/if}
</aside>
