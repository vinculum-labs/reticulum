<script lang="ts">
  import { app, deviceAt, deviceName } from "../../lib/state.svelte";
  import { KIND_LABEL, STATUS_LABEL, formatRate } from "../../lib/kinds";
  import { posKey, type GraphNode, type Sheet } from "../../lib/types";

  const snapshot = $derived(app.snapshot!);

  /** Every node on every sheet, with its sheet. */
  const nodes = $derived(
    snapshot.sheets.flatMap((sheet) => (sheet.nodes ?? []).map((node) => ({ sheet, node }))),
  );

  function nodeName(node: GraphNode): string {
    const s = node.settings;
    if (node.label) return node.label;
    if ("device" in s) return deviceName(deviceAt(posKey(s.device)));
    return KIND_LABEL[s.kind];
  }

  function open(sheet: Sheet, node?: GraphNode) {
    app.sheetId = sheet.id;
    app.selectedNodes = node ? [node.id] : [];
    app.selectedEdges = [];
    app.selectedDevice = null;
    app.panel = null;
  }

  const top = $derived(
    nodes
      .filter((x) => (app.rates[x.node.id] ?? 0) > 0)
      .sort((a, b) => (app.rates[b.node.id] ?? 0) - (app.rates[a.node.id] ?? 0))
      .slice(0, 10),
  );
  const blocked = $derived(nodes.filter((x) => app.status[x.node.id] && app.status[x.node.id] !== "switched_off"));
  /** Everything switched off, so nothing stays off by accident. */
  const off = $derived([
    ...snapshot.sheets
      .filter((s) => !s.subsheet && (s.enabled === false || app.sheetsOff.includes(s.id)))
      .map((s) => ({ sheet: s, node: undefined as GraphNode | undefined, why: s.enabled === false ? "sheet off" : "sheet off by redstone" })),
    ...snapshot.disabledFolders.map((folder) => ({ folder, why: "folder off" })),
    ...nodes
      .filter((x) => x.node.enabled === false || app.status[x.node.id] === "switched_off")
      .map((x) => ({ sheet: x.sheet, node: x.node, why: x.node.enabled === false ? "node off" : "off by redstone" })),
  ]);
  const overflow = $derived([
    ...snapshot.overflow.items.map((s) => `${s.count}× ${s.id}`),
    ...snapshot.overflow.fluids.map((f) => `${f.amount} mB ${f.id}`),
    ...(snapshot.overflow.chemicals ?? []).map((c) => `${c.amount} mB ${c.id}`),
  ]);
</script>

<div class="flex flex-col gap-4 text-sm">
  <div class="flex items-center justify-between">
    <h2 class="text-base font-semibold">Dashboard</h2>
    <button class="btn text-sm" onclick={() => (app.panel = null)}>Close</button>
  </div>

  <section class="flex flex-col gap-1">
    <h3 class="panel-title">Tick cost</h3>
    {#if app.tick}
      <div class="tabular-nums" style={app.tick.average > 1 ? "color: var(--warn)" : ""}>
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
      <button class="flex justify-between gap-2 text-left" onclick={() => open(x.sheet, x.node)}>
        <span class="truncate">{nodeName(x.node)} <span class="muted">· {x.sheet.name}</span></span>
        <span class="tabular-nums">{formatRate(app.rates[x.node.id] ?? 0, null, app.rateUnit)}</span>
      </button>
    {:else}
      <p class="muted">Nothing is moving.</p>
    {/each}
  </section>

  <section class="flex flex-col gap-1">
    <h3 class="panel-title">Waiting ({blocked.length})</h3>
    {#each blocked as x (x.node.id)}
      <button class="flex justify-between gap-2 text-left" onclick={() => open(x.sheet, x.node)}>
        <span class="truncate">{nodeName(x.node)} <span class="muted">· {x.sheet.name}</span></span>
        <span style="color: var(--warn)">{STATUS_LABEL[app.status[x.node.id]] ?? app.status[x.node.id]}</span>
      </button>
    {:else}
      <p class="muted">Nothing is waiting.</p>
    {/each}
  </section>

  <section class="flex flex-col gap-1">
    <h3 class="panel-title">Switched off ({off.length})</h3>
    {#each off as x}
      {#if "folder" in x}
        <div class="flex justify-between gap-2"><span class="truncate">📁 {x.folder}</span><span class="muted">{x.why}</span></div>
      {:else}
        <button class="flex justify-between gap-2 text-left" onclick={() => open(x.sheet, x.node)}>
          <span class="truncate">{x.node ? nodeName(x.node) : x.sheet.name}{#if x.node}<span class="muted"> · {x.sheet.name}</span>{/if}</span>
          <span class="muted">{x.why}</span>
        </button>
      {/if}
    {:else}
      <p class="muted">Everything is on.</p>
    {/each}
  </section>

  <section class="flex flex-col gap-1">
    <h3 class="panel-title">Overflow buffer</h3>
    {#each overflow as line}<div>{line}</div>{:else}<p class="muted">Empty.</p>{/each}
  </section>
</div>
