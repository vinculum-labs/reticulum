<script lang="ts">
  import { app, nodeTitle, openSheet } from "../../lib/state.svelte";
  import { offReason, sheetPath } from "../../lib/tree";
  import InspectorPanel from "./InspectorPanel.svelte";
  import { STATUS_LABEL, formatRate } from "../../lib/kinds";
  import type { GraphNode, Sheet } from "../../lib/types";

  const snapshot = $derived(app.snapshot!);

  /** Every node on every sheet, with its sheet. */
  const nodes = $derived(
    snapshot.sheets.flatMap((sheet) => (sheet.nodes ?? []).map((node) => ({ sheet, node }))),
  );

  function open(sheet: Sheet, node?: GraphNode) {
    openSheet(sheet.id, node ? [node.id] : [], true);
  }

  const top = $derived(
    nodes
      .filter((x) => (app.rates[x.node.id] ?? 0) > 0)
      .sort((a, b) => (app.rates[b.node.id] ?? 0) - (app.rates[a.node.id] ?? 0))
      .slice(0, 10),
  );
  const blocked = $derived(nodes.filter((x) => app.status[x.node.id] && app.status[x.node.id] !== "switched_off"));
  /**
   * Everything switched off, so nothing stays off by accident: sheets (by their own switch, a sheet above, or a gate),
   * then nodes (sheet nodes are listed as their sheets).
   */
  const off = $derived([
    ...snapshot.sheets.flatMap((s) => {
      const why = offReason(snapshot, s, app.sheetsOff);
      if (!why || why.kind === "network") return [];
      const text = why.kind === "self" ? "sheet off" : why.kind === "parent" ? `inside “${why.sheet.name}”, off` : "off by a gate or redstone";
      return [{ sheet: s, node: undefined as GraphNode | undefined, why: text }];
    }),
    ...nodes
      .filter((x) => x.node.settings.kind !== "sheet" && (x.node.enabled === false || app.status[x.node.id] === "switched_off"))
      .map((x) => ({ sheet: x.sheet, node: x.node, why: x.node.enabled === false ? "node off" : "off by its gate" })),
  ]);
  const overflow = $derived([
    ...snapshot.overflow.items.map((s) => `${s.count}× ${s.id}`),
    ...snapshot.overflow.fluids.map((f) => `${f.amount} mB ${f.id}`),
    ...(snapshot.overflow.chemicals ?? []).map((c) => `${c.amount} mB ${c.id}`),
  ]);
</script>

<InspectorPanel title="Dashboard" onclose={() => (app.panel = null)}>
<div class="flex flex-col gap-4 text-sm">
  <section class="flex flex-col gap-1">
    <h3 class="panel-title">Tick cost</h3>
    {#if app.tick}
      <div class="tabular-nums" class:text-warn={app.tick.average > 1}>
        {app.tick.average.toFixed(3)} ms average, {app.tick.longest.toFixed(3)} ms longest
      </div>
      <p class="text-xs muted">Over the last 200 ticks. A tick has 50 ms for everything on the server.</p>
    {:else}
      <p class="muted">Not running.</p>
    {/if}
  </section>

  <section class="flex flex-col gap-1">
    <h3 class="panel-title">Busiest</h3>
    {#each top as x (x.node.id)}
      <button class="list-row flex justify-between gap-2 rounded px-1 text-left" onclick={() => open(x.sheet, x.node)}>
        <span class="truncate">{nodeTitle(x.node)} <span class="muted">· {sheetPath(snapshot, x.sheet)}</span></span>
        <span class="tabular-nums">{formatRate(app.rates[x.node.id] ?? 0, null, app.rateUnit)}</span>
      </button>
    {:else}
      <p class="muted">Nothing is moving.</p>
    {/each}
  </section>

  <section class="flex flex-col gap-1">
    <h3 class="panel-title">Waiting ({blocked.length})</h3>
    {#each blocked as x (x.node.id)}
      <button class="list-row flex justify-between gap-2 rounded px-1 text-left" onclick={() => open(x.sheet, x.node)}>
        <span class="truncate">{nodeTitle(x.node)} <span class="muted">· {sheetPath(snapshot, x.sheet)}</span></span>
        <span class="text-warn">{STATUS_LABEL[app.status[x.node.id]] ?? app.status[x.node.id]}</span>
      </button>
    {:else}
      <p class="muted">Nothing is waiting.</p>
    {/each}
  </section>

  <section class="flex flex-col gap-1">
    <h3 class="panel-title">Switched off ({off.length})</h3>
    {#each off as x}
      <button class="list-row flex justify-between gap-2 rounded px-1 text-left" onclick={() => open(x.sheet, x.node)}>
        <span class="truncate">{x.node ? nodeTitle(x.node) : sheetPath(snapshot, x.sheet)}{#if x.node}<span class="muted"> · {sheetPath(snapshot, x.sheet)}</span>{/if}</span>
        <span class="muted">{x.why}</span>
      </button>
    {:else}
      <p class="muted">Everything is on.</p>
    {/each}
  </section>

  <section class="flex flex-col gap-1">
    <h3 class="panel-title">Overflow buffer</h3>
    {#each overflow as line}<div>{line}</div>{:else}<p class="muted">Empty.</p>{/each}
  </section>
</div>
</InspectorPanel>
