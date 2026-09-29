<script lang="ts">
  import { onDestroy, untrack } from "svelte";
  import uPlot from "uplot";
  import "uplot/dist/uPlot.min.css";
  import { app, request } from "../../lib/state.svelte";

  let { item, unit }: { item: string; unit: string } = $props();

  // The id as a value: `item` is passed as `node.id`, so reading it directly would re-run the effect below whenever
  // the node object is replaced (every network update, e.g. after each move), rebuilding the chart for nothing.
  const itemId = $derived(item);

  let host: HTMLDivElement | undefined = $state();
  let window_ = $state<"second" | "minute">("second");
  let plot: uPlot | null = null;
  let timer: ReturnType<typeof setInterval> | undefined;

  function css(name: string): string {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  }

  async function load() {
    if (!host) return;
    let values: number[];
    try {
      values = (await request("history", { item: itemId, window: window_ })).values;
    } catch {
      return;
    }
    const step = window_ === "second" ? 1 : 60;
    // Each value covers one second or one minute; show it as an average per tick or per second.
    const ticksPerValue = step * 20;
    values = values.map((v) => (app.rateUnit === "t" ? v / ticksPerValue : v / step));
    const now = Math.floor(Date.now() / 1000);
    const xs = values.map((_, i) => now - (values.length - 1 - i) * step);
    const data: uPlot.AlignedData = [xs, values];
    if (plot) {
      plot.setData(data);
      return;
    }
    const axis = { stroke: css("--muted"), grid: { stroke: css("--grid"), width: 1 }, ticks: { stroke: css("--grid") } };
    plot = new uPlot(
      {
        width: host.clientWidth,
        height: 120,
        legend: { show: false },
        cursor: { y: false },
        scales: { x: { time: true }, y: { range: (_u, _min, max) => [0, Math.max(1, max * 1.1)] } },
        axes: [axis, { ...axis, size: 48 }],
        series: [{}, { stroke: css("--accent"), fill: css("--accent") + "2e", width: 1.5, label: unit }],
      },
      data,
      host,
    );
  }

  // Rebuilt only for another item, window or unit; loading itself subscribes to nothing.
  $effect(() => {
    void itemId;
    void window_;
    void app.rateUnit;
    plot?.destroy();
    plot = null;
    untrack(load);
    clearInterval(timer);
    timer = setInterval(() => untrack(load), window_ === "second" ? 2000 : 30000);
  });

  onDestroy(() => {
    clearInterval(timer);
    plot?.destroy();
  });
</script>

<div class="flex flex-col gap-1">
  <div class="flex items-center justify-between">
    <span class="text-xs font-medium muted"
      >Throughput{unit ? ` (${unit}` : " ("}/{app.rateUnit}, {window_ === "second" ? "last 5 min" : "last 24 h, per-minute averages"})</span
    >
    <select bind:value={window_} class="text-xs">
      <option value="second">5 min</option>
      <option value="minute">24 h</option>
    </select>
  </div>
  <div bind:this={host} class="w-full"></div>
</div>
