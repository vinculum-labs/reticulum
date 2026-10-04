<script lang="ts">
  import { onDestroy } from "svelte";
  import { app, deviceName, edit, request } from "../../lib/state.svelte";
  import { loadCatalog, type Catalog, type CatalogKind } from "../../lib/catalog";
  import { RESOURCE_LABEL, RESOURCE_UNIT } from "../../lib/kinds";
  import { posKey, type ChartSeries, type Resource } from "../../lib/types";
  import InspectorPanel from "./InspectorPanel.svelte";
  import Segmented from "../controls/Segmented.svelte";
  import Select from "../controls/Select.svelte";
  import TextField from "../controls/TextField.svelte";
  import RemoveButton from "../controls/RemoveButton.svelte";
  import Help from "./Help.svelte";
  import ChartPlot, { type PlotLine } from "./ChartPlot.svelte";

  /**
   * The network's charts in one place: what its flows move of each resource (the total, and each item, fluid or
   * chemical charted), and what charted devices hold. Players choose what is charted beyond the totals (16 at most).
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

  function chartLabel(c: ChartSeries): string {
    if (c.kind === "level") {
      const device = c.device ? snapshot.devices.find((d) => posKey(d.pos) === posKey(c.device!)) : undefined;
      return `${device ? deviceName(device) : "a removed device"} · ${RESOURCE_LABEL[c.resource ?? "item"].toLowerCase()}`;
    }
    const entry = catalog?.[c.kind as CatalogKind]?.find((e) => e.id === c.id);
    return entry?.name ?? c.id ?? "?";
  }

  /** Every series shown: totals and charted throughputs, and levels. */
  const wanted = $derived([...MOVED.map((r) => totals[r]).filter(Boolean), ...charts.map((c) => c.series)]);

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
  function rate(v: number[]): number[] {
    return v.map((x) => (app.rateUnit === "t" ? x / (step * 20) : x / step));
  }
  /** A level as it stood: per minute, the average of the minute's samples. */
  function level(v: number[]): number[] {
    return v.map((x) => x / step);
  }

  const groups = $derived(
    MOVED.map((resource) => {
      const lines: PlotLine[] = [];
      if (totals[resource]) lines.push({ label: `All ${RESOURCE_LABEL[resource].toLowerCase()}`, values: rate(values[totals[resource]] ?? []), color: PALETTE[0] });
      charts
        .filter((c) => c.kind !== "level" && (c.kind === "item" ? "item" : c.kind) === resource)
        .forEach((c, i) => lines.push({ label: chartLabel(c), values: rate(values[c.series] ?? []), color: PALETTE[(i + 1) % PALETTE.length] }));
      const any = lines.some((l) => l.values.some((x) => x > 0)) || lines.length > 1;
      return { resource, lines, any };
    }).filter((g) => g.any),
  );
  const levels = $derived(charts.filter((c) => c.kind === "level"));

  // ---------------------------------------------------------------- adding

  let catalog = $state<Catalog | null>(null);
  loadCatalog().then((c) => (catalog = c)).catch(() => {});
  let addKind = $state<"item" | "fluid" | "chemical" | "level">("item");
  let addId = $state("");
  let addDevice = $state("");
  let addResource = $state<Resource>("energy");
  const suggestions = $derived(addKind === "level" || !catalog ? [] : (catalog[addKind] ?? []).map((e) => e.id));
  const heldResources = $derived.by(() => {
    const device = snapshot.devices.find((d) => posKey(d.pos) === addDevice);
    return (["energy", "item", "fluid", "chemical"] as Resource[]).filter((r) => device?.resources?.[r]?.length);
  });

  function add() {
    let chart: Omit<ChartSeries, "key" | "series">;
    if (addKind === "level") {
      const device = snapshot.devices.find((d) => posKey(d.pos) === addDevice);
      if (!device) return;
      chart = { kind: "level", device: device.pos, resource: heldResources.includes(addResource) ? addResource : heldResources[0] ?? "energy" };
    } else {
      const id = addId.trim();
      if (!id) return;
      chart = { kind: addKind, id: id.includes(":") ? id : `minecraft:${id}` };
    }
    edit([{ type: "addChart", chart }], undefined, "chart");
    addId = "";
  }

  function remove(c: ChartSeries) {
    const { key, series, ...chart } = c;
    void series;
    edit([{ type: "removeChart", key }], [{ type: "addChart", chart }], "stop charting");
  }
</script>

<InspectorPanel title="Charts" onclose={() => (app.panel = null)}>
  <div class="flex flex-col gap-4 text-sm">
    <div class="flex items-center justify-between">
      <span class="muted">{window_ === "second" ? "Last 5 minutes" : "Last 24 hours, per-minute averages"}</span>
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
    </div>
    {#if failed}<p class="text-xs muted" role="status">Couldn't load the history from the game.</p>{/if}

    {#each groups as group (group.resource)}
      <section class="flex flex-col gap-1">
        <h3 class="panel-title">{RESOURCE_LABEL[group.resource]} moved ({RESOURCE_UNIT[group.resource]}/{app.rateUnit})</h3>
        <ChartPlot lines={group.lines} {step} />
      </section>
    {:else}
      <p class="muted">Nothing has moved yet.</p>
    {/each}

    {#each levels as c (c.key)}
      <section class="flex flex-col gap-1">
        <h3 class="panel-title">{chartLabel(c)} ({RESOURCE_UNIT[c.resource ?? "item"]} held)</h3>
        <ChartPlot lines={[{ label: chartLabel(c), values: level(values[c.series] ?? []), color: PALETTE[3] }]} {step} />
      </section>
    {/each}

    <section class="flex flex-col gap-2">
      <h3 class="panel-title">Charted ({charts.length}/16)</h3>
      {#each charts as c (c.key)}
        <div class="flex items-center gap-2">
          <span class="min-w-0 flex-1 truncate">{chartLabel(c)} <span class="muted">· {c.kind === "level" ? "held" : "moved"}</span></span>
          {#if snapshot.canEdit}<RemoveButton label={`Stop charting ${chartLabel(c)}`} onclick={() => remove(c)} />{/if}
        </div>
      {:else}
        <p class="muted">Only the totals — chart an item, fluid or chemical, or what a device holds.</p>
      {/each}
      {#if snapshot.canEdit && charts.length < 16}
        <div class="flex flex-col gap-2">
          <Select
            value={addKind}
            options={[
              { value: "item", label: "An item moved" },
              { value: "fluid", label: "A fluid moved" },
              { value: "chemical", label: "A chemical moved" },
              { value: "level", label: "What a device holds" },
            ]}
            onchange={(v) => (addKind = v as typeof addKind)}
          />
          {#if addKind === "level"}
            <Select
              value={addDevice}
              options={[{ value: "", label: "Pick a device…" }, ...snapshot.devices.filter((d) => d.state === "active").map((d) => ({ value: posKey(d.pos), label: deviceName(d) }))]}
              onchange={(v) => (addDevice = v)}
            />
            {#if heldResources.length > 1}
              <Select
                value={heldResources.includes(addResource) ? addResource : heldResources[0]}
                options={heldResources.map((r) => ({ value: r, label: RESOURCE_LABEL[r] }))}
                onchange={(v) => (addResource = v as Resource)}
              />
            {/if}
          {:else}
            <TextField value={addId} placeholder="minecraft:iron_ingot" {suggestions} oncommit={(v) => (addId = v)} oninput={(v) => (addId = v)} />
          {/if}
          <button class="btn self-start" disabled={addKind === "level" ? !addDevice : !addId.trim()} onclick={add}>Chart it</button>
        </div>
      {/if}
      <Help>Totals are always kept. Anything else is recorded only once charted, so the server keeps no more than it must. Charts start when charted and reset when the server restarts.</Help>
    </section>
  </div>
</InspectorPanel>
