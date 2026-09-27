<script lang="ts">
  import { app, edit, deviceName, deviceAt } from "../../lib/state.svelte";
  import { KIND_LABEL } from "../../lib/kinds";
  import { posKey, type GraphNode, type Sheet, type Wire } from "../../lib/types";
  import Chart from "./Chart.svelte";

  let { wire, sheet }: { wire: Wire; sheet: Sheet } = $props();

  function name(id: string): string {
    const n: GraphNode | undefined = sheet.nodes?.find((x) => x.id === id);
    if (!n) return "?";
    if (n.label) return n.label;
    if (n.settings.kind === "device") return deviceName(deviceAt(posKey(n.settings.device)));
    return KIND_LABEL[n.settings.kind];
  }
</script>

<div class="flex flex-col gap-3">
  <div class="flex items-center justify-between">
    <h2 class="text-base font-semibold">Wire</h2>
    {#if app.snapshot?.canEdit}
      <button
        class="btn btn-danger text-sm"
        onclick={() => {
          app.selectedEdges = [];
          edit([{ type: "disconnect", sheet: sheet.id, wire: wire.id }], [{ type: "connect", sheet: sheet.id, wire }], "delete wire");
        }}>Delete</button
      >
    {/if}
  </div>
  <p class="text-sm">{name(wire.from)} {wire.output === 1 ? "(rejects)" : ""} → {name(wire.to)}</p>
  <p class="text-sm muted">Moving {app.rates[wire.id] ?? 0} per second.</p>
  <Chart item={wire.id} unit="per second" />
</div>
