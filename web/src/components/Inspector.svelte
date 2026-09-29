<script lang="ts">
  import { app, currentSheet, deviceAt } from "../lib/state.svelte";
  import NodeInspector from "./inspector/NodeInspector.svelte";
  import WireInspector from "./inspector/WireInspector.svelte";
  import DeviceInspector from "./inspector/DeviceInspector.svelte";
  import SheetInspector from "./inspector/SheetInspector.svelte";
  import NetworkSettings from "./inspector/NetworkSettings.svelte";
  import IdentifiedPanel from "./inspector/IdentifiedPanel.svelte";
  import Dashboard from "./inspector/Dashboard.svelte";

  const sheet = $derived(currentSheet());
  const node = $derived(app.selectedNodes.length === 1 ? sheet?.nodes?.find((n) => n.id === app.selectedNodes[0]) : undefined);
  const wire = $derived(
    !node && app.selectedEdges.length === 1 ? sheet?.wires?.find((w) => w.id === app.selectedEdges[0]) : undefined,
  );
  const device = $derived(app.selectedDevice ? deviceAt(app.selectedDevice) : undefined);
</script>

<aside class="flex w-80 shrink-0 flex-col overflow-y-auto border-l p-3" style="background: var(--panel); border-color: var(--line)">
  {#if app.panel === "network" && app.snapshot}
    <NetworkSettings />
  {:else if app.panel === "identified" && app.snapshot}
    <IdentifiedPanel />
  {:else if app.panel === "dashboard" && app.snapshot}
    <Dashboard />
  {:else if node && sheet}
    {#key node.id}
      <NodeInspector {node} {sheet} />
    {/key}
  {:else if wire && sheet}
    <WireInspector {wire} {sheet} />
  {:else if app.selectedNodes.length > 1}
    <p class="muted">{app.selectedNodes.length} nodes selected. Drag to move them together, or press Delete.</p>
  {:else if device}
    {#key app.selectedDevice}
      <DeviceInspector {device} />
    {/key}
  {:else if sheet}
    {#key sheet.id}
      <SheetInspector {sheet} />
    {/key}
  {:else}
    <p class="muted">Nothing selected.</p>
  {/if}
</aside>
