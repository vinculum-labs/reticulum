<script lang="ts">
  import { onDestroy } from "svelte";
  import X from "@lucide/svelte/icons/x";
  import { app, deviceName, edit, request } from "../../lib/state.svelte";
  import { iconUrl, loadCatalog, matchesSearch, type Catalog, type CatalogKind } from "../../lib/catalog";
  import { RESOURCE_LABEL, RESOURCE_UNIT } from "../../lib/kinds";
  import { posKey, type ChartSeries, type Resource } from "../../lib/types";
  import Segmented from "../controls/Segmented.svelte";
  import Select from "../controls/Select.svelte";
  import TextField from "../controls/TextField.svelte";
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
  let timer: ReturnType<typeof setInterval> | undefined;
  let generation = 0;

  const MOVED: Resource[] = ["energy", "item", "fluid", "chemical"];
  const PALETTE = ["var(--accent)", "#e0a846", "#5fb878", "#c678dd", "#56b6c2", "#e06c75", "#d19a66", "#98c379", "#61afef"];

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

  async function load() {
    const asked = ++generation;
    try {
      const loaded = await Promise.all(wanted.map((id) => request("history", { item: id, window: window_ }).then((r) => [id, r.values as number[]] as const)));
      if (asked !== generation) return;
      values = Object.fromEntries(loaded);
      failed = false;
    } catch {
      if (asked === generation) failed = true;
    }
  }

  $effect(() => {
    void wanted.join();
    void window_;
    load();
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
      const lines: PlotLine[] = [{ label: `All ${RESOURCE_LABEL[resource].toLowerCase()}`, values: rate(values[totals[resource] ?? ""]), color: PALETTE[0] }];
      charts
        .filter((c) => (c.kind === "item" || c.kind === "fluid" || c.kind === "chemical") && c.kind === resource)
        .forEach((c, i) => lines.push({ label: chartLabel(c), values: rate(values[c.series]), color: PALETTE[(i + 1) % PALETTE.length] }));
      return { resource, lines };
    }),
  );
  const devices = $derived(charts.filter((c) => c.kind === "level" || c.kind === "through"));

  // ---------------------------------------------------------------- adding

  let catalog = $state<Catalog | null>(null);
  loadCatalog().then((c) => (catalog = c)).catch(() => {});
  let addKind = $state<"item" | "fluid" | "chemical" | "level" | "through">("item");
  let addId = $state("");
  let addDevice = $state("");
  let addResource = $state<Resource>("energy");
  let search = $state("");
  const forDevice = $derived(addKind === "level" || addKind === "through");
  /** Up to 8 entries of the kind matching what is typed (a name, an id, "@mod" or "#tag"). */
  const matches = $derived.by(() => {
    if (forDevice || !catalog || !search.trim()) return [];
    const words = search.toLowerCase().trim().split(/\s+/);
    const out = [];
    for (const entry of catalog[addKind as CatalogKind] ?? []) {
      if (matchesSearch(entry, words)) {
        out.push(entry);
        if (out.length === 8) break;
      }
    }
    return out;
  });
  const picked = $derived(forDevice || !catalog ? undefined : (catalog[addKind as CatalogKind] ?? []).find((e) => e.id === addId));
  const deviceResources = $derived.by(() => {
    const device = snapshot.devices.find((d) => posKey(d.pos) === addDevice);
    return (["energy", "item", "fluid", "chemical"] as Resource[]).filter((r) => device?.resources?.[r]?.length);
  });
  const resourceChosen = $derived(deviceResources.includes(addResource) ? addResource : deviceResources[0]);

  function add() {
    let chart: Partial<ChartSeries>;
    if (forDevice) {
      const device = snapshot.devices.find((d) => posKey(d.pos) === addDevice);
      if (!device || !resourceChosen) return;
      chart = { kind: addKind, device: device.pos, resource: resourceChosen };
    } else {
      if (!addId) return;
      chart = { kind: addKind, id: addId };
    }
    edit([{ type: "addChart", chart }], undefined, "chart");
    addId = "";
    search = "";
  }

  function remove(c: ChartSeries) {
    const chart = { kind: c.kind, id: c.id, device: c.device, resource: c.resource };
    edit([{ type: "removeChart", key: c.key }], [{ type: "addChart", chart }], "stop charting");
  }
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
      onchange={(v) => (window_ = v)}
    />
    <button class="btn px-2" title="Back to the sheet" aria-label="Close charts" onclick={() => (app.panel = null)}><X size={16} /></button>
  </div>
  <div class="flex min-h-0 flex-1">
    <div class="min-w-0 flex-1 overflow-y-auto p-4">
      {#if failed}<p class="mb-2 text-xs muted" role="status">Couldn't load the history from the game.</p>{/if}
      <div class="charts-grid">
        {#each groups as group (group.resource)}
          <section class="card">
            <h3 class="panel-title">{RESOURCE_LABEL[group.resource]} moved ({RESOURCE_UNIT[group.resource]}/{app.rateUnit})</h3>
            <ChartPlot lines={group.lines} {step} />
          </section>
        {/each}
        {#each devices as c (c.key)}
          <section class="card">
            {#if c.kind === "level"}
              <h3 class="panel-title">{deviceLabel(c)} — {RESOURCE_LABEL[c.resource ?? "item"].toLowerCase()} held ({RESOURCE_UNIT[c.resource ?? "item"]})</h3>
              <ChartPlot lines={[{ label: "Held", values: level(values[c.series]), color: PALETTE[3] }]} {step} />
            {:else}
              <h3 class="panel-title">{deviceLabel(c)} — {RESOURCE_LABEL[c.resource ?? "item"].toLowerCase()} in and out ({RESOURCE_UNIT[c.resource ?? "item"]}/{app.rateUnit})</h3>
              <ChartPlot
                lines={[
                  { label: "Into it", values: rate(values[c.seriesIn ?? ""]), color: PALETTE[2] },
                  { label: "Out of it", values: rate(values[c.seriesOut ?? ""]), color: PALETTE[5] },
                ]}
                {step}
              />
            {/if}
          </section>
        {/each}
      </div>
    </div>
    <aside class="flex w-72 shrink-0 flex-col gap-2 overflow-y-auto border-l border-line bg-panel p-3 text-sm">
      <h3 class="panel-title">Charted ({charts.length}/16)</h3>
      {#each charts as c (c.key)}
        <div class="flex items-center gap-2">
          <span class="min-w-0 flex-1 truncate"
            >{chartLabel(c)} <span class="muted">· {c.kind === "level" ? "held" : c.kind === "through" ? "in and out" : "moved"}</span></span
          >
          {#if snapshot.canEdit}<RemoveButton label={`Stop charting ${chartLabel(c)}`} onclick={() => remove(c)} />{/if}
        </div>
      {:else}
        <p class="muted">Only the totals so far.</p>
      {/each}
      {#if snapshot.canEdit && charts.length < 16}
        <h3 class="panel-title mt-2">Chart</h3>
        <Select
          value={addKind}
          options={[
            { value: "item", label: "An item moved" },
            { value: "fluid", label: "A fluid moved" },
            { value: "chemical", label: "A chemical moved" },
            { value: "through", label: "What moves into and out of a device" },
            { value: "level", label: "What a device holds" },
          ]}
          onchange={(v) => (addKind = v as typeof addKind)}
        />
        {#if forDevice}
          <Select
            value={addDevice}
            options={[{ value: "", label: "Pick a device…" }, ...snapshot.devices.filter((d) => d.state === "active").map((d) => ({ value: posKey(d.pos), label: deviceName(d) }))]}
            onchange={(v) => (addDevice = v)}
          />
          {#if deviceResources.length > 1}
            <Select value={resourceChosen} options={deviceResources.map((r) => ({ value: r, label: RESOURCE_LABEL[r] }))} onchange={(v) => (addResource = v as Resource)} />
          {:else if addDevice && deviceResources.length === 0}
            <p class="text-xs muted">That device has no energy, items, fluid or chemical to chart.</p>
          {/if}
        {:else}
          <TextField
            value={search}
            placeholder={`Search ${addKind === "item" ? "items" : addKind === "fluid" ? "fluids" : "chemicals"} by name or id`}
            oncommit={(v) => (search = v)}
            oninput={(v) => (search = v)}
          />
          {#each matches as entry (entry.id)}
            <button
              class="list-row flex items-center gap-2 rounded px-1 text-left"
              style={entry.id === addId ? "background: var(--panel-2); outline: 1px solid var(--accent)" : ""}
              onclick={() => (addId = entry.id)}
            >
              <img src={iconUrl(addKind as CatalogKind, entry.id)} alt="" width="16" height="16" class="shrink-0" style="image-rendering: pixelated" />
              <span class="min-w-0 flex-1 truncate">{entry.name}</span>
              <span class="shrink-0 text-xs muted">{entry.id}</span>
            </button>
          {/each}
          {#if picked}<span class="text-xs">Picked: <strong>{picked.name}</strong> <span class="muted">{picked.id}</span></span>{/if}
        {/if}
        <button class="btn self-start" disabled={forDevice ? !addDevice || !resourceChosen : !addId} onclick={add}>Chart it</button>
      {/if}
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
