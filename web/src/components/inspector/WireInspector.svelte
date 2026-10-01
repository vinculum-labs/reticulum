<script lang="ts">
  import { useSvelteFlow } from "@xyflow/svelte";
  import { app, nodeTitle } from "../../lib/state.svelte";
  import { type Sheet, type Wire } from "../../lib/types";
  import Chart from "./Chart.svelte";
  import InspectorPanel from "./InspectorPanel.svelte";
  import ThroughputSummary from "./ThroughputSummary.svelte";

  let { wire, sheet }: { wire: Wire; sheet: Sheet } = $props();

  const from = $derived(sheet.nodes?.find((n) => n.id === wire.from));
  const to = $derived(sheet.nodes?.find((n) => n.id === wire.to));
  /** A filter's second output is where what doesn't match goes; other nodes' outputs are just numbered. */
  const rejects = $derived(from?.settings.kind === "filter" && wire.output === 1);

  const flow = useSvelteFlow();

  /** Deletes the wire the way the Delete key does. */
  function remove() {
    flow.deleteElements({ edges: [{ id: wire.id }] });
  }
</script>

<InspectorPanel title="Wire" subtitle={`${nodeTitle(from)}${rejects ? " (rejects)" : ""} → ${nodeTitle(to)}`}>
  {#snippet actions()}
    {#if app.snapshot?.canEdit}<button class="btn btn-danger text-sm" onclick={remove}>Delete</button>{/if}
  {/snippet}
  <ThroughputSummary id={wire.id} />
  <Chart item={wire.id} unit="" />
</InspectorPanel>
