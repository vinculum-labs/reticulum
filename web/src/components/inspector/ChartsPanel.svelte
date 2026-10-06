<script lang="ts">
  import { onDestroy } from "svelte";
  import X from "@lucide/svelte/icons/x";
  import { app, deviceName, request } from "../../lib/state.svelte";
  import { loadCatalog, type Catalog, type CatalogKind } from "../../lib/catalog";
  import { addChart, removeChart } from "../../lib/charts";
  import CatalogPickDialog from "./CatalogPickDialog.svelte";
  import Plus from "@lucide/svelte/icons/plus";
  import { RESOURCE_LABEL, RESOURCE_UNIT, RESOURCE_VAR } from "../../lib/kinds";
  import { posKey, type ChartSeries, type Resource } from "../../lib/types";
  import Segmented from "../controls/Segmented.svelte";
  import RemoveButton from "../controls/RemoveButton.svelte";
  import Help from "./Help.svelte";
  import ChartPlot, { type PlotLine } from "./ChartPlot.svelte";

  /**
   * The network's charts, over the canvas: what its flows move of each resource (the total, and each item, fluid or
   * chemical charted), what charted devices hold, and what moves into and out of charted devices. Players choose what
   * is charted beyond the totals (16 at most).
   */
  const snapshot = $derived(app.snapshot!);
  const charts = $derived(snapshot.charts ?? []);
  const totals = $derived(snapshot.chartTotals ?? {});
  let window_ = $state<"second" | "minute">("second");
  /** Every series' values, by series id, as last loaded. */
  let values = $state<Record<string, number[]>>({});
  let failed = $state(false);
  /** When each series' newest value ended (epoch seconds, from the game). */
  let newest = $state<Record<string, number>>({});
  /** The window the values shown are for. */
  let valuesWindow: unknown = undefined;
  let timer: ReturnType<typeof setInterval> | undefined;
  let generation = 0;
  /** A load still waiting on the game: the next tick skips (a slow server isn't sent more while it catches up). */
  let loading = false;
  // Not closed by Esc: like a tab, it stays open while Esc leaves the editor for the game, and is there on coming back.

  const MOVED: Resource[] = ["energy", "item", "fluid", "chemical"];
  /** Charted things in one chart: shades of its type's color, taking turns lighter and darker. */
  const SHADES = [0.45, -0.35, 0.7, -0.55, 0.25, -0.2, 0.85];

  function deviceLabel(c: ChartSeries): string {
    const device = c.device ? snapshot.devices.find((d) => posKey(d.pos) === posKey(c.device!)) : undefined;
    return device ? deviceName(device) : "a removed device";
  }

  function chartLabel(c: ChartSeries): string {
    if (c.kind === "level" || c.kind === "through") return `${deviceLabel(c)} · ${RESOURCE_LABEL[c.resource ?? "item"].toLowerCase()}`;
    const entry = catalog?.[c.kind as CatalogKind]?.find((e) => e.id === c.id);
    return entry?.name ?? c.id ?? "?";
  }

  /** Every series to load: totals, charted throughputs and levels, and both sides of what passes through devices. */
  const wanted = $derived([
    ...MOVED.map((r) => totals[r]).filter((id): id is string => !!id),
    ...charts.flatMap((c) => (c.kind === "through" ? [c.seriesIn ?? "", c.seriesOut ?? ""] : [c.series])).filter(Boolean),
  ]);

  async function load(force = false) {
    if (loading && !force) return;
    const asked = ++generation;
    loading = true;
    try {
      // Each on its own: one series that fails (a timeout) keeps its last values, the rest still update.
      const settled = await Promise.allSettled(
        wanted.map((id) => request("history", { item: id, window: window_ }).then((r) => [id, r.values as number[], r.newest as number | undefined] as const)),
      );
      if (asked !== generation) return;
      const loaded = settled.flatMap((r) => (r.status === "fulfilled" ? [[r.value[0], r.value[1]] as const] : []));
      for (const r of settled) if (r.status === "fulfilled" && r.value[2] !== undefined) newest[r.value[0]] = r.value[2];
      // Kept only for the same window: per-second values don't belong on a per-minute chart.
      values = { ...(valuesWindow === window_ ? values : {}), ...Object.fromEntries(loaded) };
      valuesWindow = window_;
      // Said when any failed (its chart then shows what it last had, or nothing).
      failed = loaded.length < settled.length;
    } catch {
      if (asked === generation) failed = true;
    } finally {
      if (asked === generation) loading = false;
    }
  }

  /** The network the values are of: another one shown, they go (its totals have the same ids). */
  let valuesOf = app.current;
  $effect(() => {
    void wanted.join();
    void window_;
    if (app.current !== valuesOf) {
      valuesOf = app.current;
      generation++;
      values = {};
      newest = {};
    }
    load(true);
    clearInterval(timer);
    timer = setInterval(load, window_ === "second" ? 2000 : 30000);
  });
  onDestroy(() => {
    generation++;
    clearInterval(timer);
  });

  const step = $derived(window_ === "second" ? 1 : 60);
  /** A throughput as a rate: per tick or per second, from a total per second (or per minute). */
  function rate(v: number[] | undefined): number[] {
    return (v ?? []).map((x) => (app.rateUnit === "t" ? x / (step * 20) : x / step));
  }
  /** A level as it stood: per minute, the average of the minute's samples. */
  function level(v: number[] | undefined): number[] {
    return (v ?? []).map((x) => x / step);
  }

  /** One chart per resource, always (flat at 0 says nothing moved): its total and what of it is charted. */
  const groups = $derived(
    MOVED.map((resource) => {
      const lines: PlotLine[] = [{ label: `All ${RESOURCE_LABEL[resource].toLowerCase()}`, key: "total", values: rate(values[totals[resource] ?? ""]), color: RESOURCE_VAR[resource] }];
      charts
        .filter((c) => (c.kind === "item" || c.kind === "fluid" || c.kind === "chemical") && c.kind === resource)
        // Past the shades, dashed: no two lines alike.
        .forEach((c, i) =>
          lines.push({ label: chartLabel(c), key: c.key, values: rate(values[c.series]), color: RESOURCE_VAR[resource], shade: SHADES[i % SHADES.length], dashed: i >= SHADES.length }),
        );
      return { resource, lines };
    }),
  );
  const devices = $derived(charts.filter((c) => c.kind === "level" || c.kind === "through"));

  // ---------------------------------------------------------------- adding

  let catalog = $state<Catalog | null>(null);
  loadCatalog().then((c) => (catalog = c)).catch(() => {});
  let picking = $state(false);
</script>

<!-- Over the canvas (which stays as it was underneath): room for the charts side by side. -->
<div class="absolute inset-0 z-10 flex flex-col overflow-hidden" style="background: var(--canvas)">
  <div class="flex items-center gap-3 border-b border-line bg-panel px-4 py-2">
    <h2 class="text-base font-semibold">Charts</h2>
    <span class="muted text-sm">{window_ === "second" ? "Last 5 minutes" : "Last 24 hours, per-minute averages"} · rates per {app.rateUnit === "t" ? "tick" : "second"}</span>
    <div class="flex-1"></div>
    <Segmented
      label="Chart window"
      size="xs"
      value={window_}
      options={[
        { value: "second", label: "5 min" },
        { value: "minute", label: "24 h" },
      ]}
      onchange={(v) => {
        // Not the other window's values meanwhile (per second read as per minute, or the other way round).
        values = {};
        window_ = v;
      }}
    />
    <button class="btn px-2" title="Back to the sheet" aria-label="Close charts" onclick={() => (app.panel = null)}><X size={16} /></button>
  </div>
  <div class="flex min-h-0 flex-1">
    <div class="min-w-0 flex-1 overflow-y-auto p-4">
      {#if failed}<p class="mb-2 text-xs muted" role="status">Couldn't load all of the history from the game.</p>{/if}
      <div class="charts-grid">
        {#each groups as group (group.resource)}
          <section class="card">
            <h3 class="panel-title">{RESOURCE_LABEL[group.resource]} moved ({RESOURCE_UNIT[group.resource]}/{app.rateUnit})</h3>
            <ChartPlot lines={group.lines} {step} newest={newest[totals[group.resource] ?? ""]} />
          </section>
        {/each}
        {#each devices as c (c.key)}
          <section class="card">
            {#if c.kind === "level"}
              <h3 class="panel-title">{deviceLabel(c)} — {RESOURCE_LABEL[c.resource ?? "item"].toLowerCase()} held ({RESOURCE_UNIT[c.resource ?? "item"]})</h3>
              <ChartPlot lines={[{ label: "Held", values: level(values[c.series]), color: RESOURCE_VAR[c.resource ?? "item"] }]} {step} newest={newest[c.series]} />
            {:else}
              <h3 class="panel-title">{deviceLabel(c)} — {RESOURCE_LABEL[c.resource ?? "item"].toLowerCase()} in and out ({RESOURCE_UNIT[c.resource ?? "item"]}/{app.rateUnit})</h3>
              <ChartPlot
                lines={[
                  { label: "Into it", values: rate(values[c.seriesIn ?? ""]), color: RESOURCE_VAR[c.resource ?? "item"] },
                  { label: "Out of it", values: rate(values[c.seriesOut ?? ""]), color: RESOURCE_VAR[c.resource ?? "item"], shade: 0.45, dashed: true },
                ]}
                {step}
                newest={newest[c.seriesIn ?? ""]}
              />
            {/if}
          </section>
        {/each}
      </div>
    </div>
    <aside class="flex w-72 shrink-0 flex-col gap-2 overflow-y-auto border-l border-line bg-panel p-3 text-sm">
      {#if snapshot.canEdit && charts.length < 16}
        <h3 class="panel-title">Chart what moves</h3>
        <!-- An action, not a value: picking charts it at once. -->
        <button class="btn flex items-center gap-1 self-start" onclick={() => (picking = true)}><Plus size={14} />Chart an item, fluid or chemical…</button>
        {#if picking}
          <CatalogPickDialog
            title="Chart what moves"
            kinds={["item", "fluid", "chemical"]}
            omit={(kind, id) => charts.some((c) => c.kind === kind && c.id === id)}
            onpick={(p) => addChart({ kind: p.kind, id: p.id })}
            onclose={() => (picking = false)}
          />
        {/if}
        <p class="text-xs muted">To chart a device — what it holds, or what moves into and out of it — right-click it in the devices list.</p>
      {/if}
      <!-- Below the picker, so its list has room to drop over it (not cut off by the panel). -->
      <h3 class="panel-title mt-2">Charted ({charts.length}/16)</h3>
      {#each charts as c (c.key)}
        <div class="flex items-center gap-2">
          <span class="min-w-0 flex-1 truncate"
            >{chartLabel(c)} <span class="muted">· {c.kind === "level" ? "held" : c.kind === "through" ? "in and out" : "moved"}</span></span
          >
          {#if snapshot.canEdit}<RemoveButton label={`Stop charting ${chartLabel(c)}`} onclick={() => removeChart(c)} />{/if}
        </div>
      {:else}
        <p class="muted">Only the totals so far.</p>
      {/each}
      <Help>Totals are always kept. Anything else is recorded only once charted. Charts start when charted and reset when the server restarts. Click a name in a chart's legend to hide or show its line.</Help>
    </aside>
  </div>
</div>

<style>
  .charts-grid {
    display: grid;
    /* Two side by side, filling the width; one per row where two don't fit. */
    grid-template-columns: repeat(auto-fit, minmax(max(min(380px, 100%), calc(50% - 6px)), 1fr));
    gap: 12px;
  }
  .card {
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 10px 12px;
    border: 1px solid var(--line);
    border-radius: 6px;
    background: var(--panel);
    min-width: 0;
  }
</style>
