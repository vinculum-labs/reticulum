<script lang="ts">
  import { onDestroy } from "svelte";
  import uPlot from "uplot";
  import "uplot/dist/uPlot.min.css";
  import { request } from "../../lib/state.svelte";

  let { item, unit }: { item: string; unit: string } = $props();

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
      values = (await request("history", { item, window: window_ })).values;
    } catch {
      return;
    }
    const step = window_ === "second" ? 1 : 60;
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

  $effect(() => {
    void window_;
    plot?.destroy();
    plot = null;
    load();
    clearInterval(timer);
    timer = setInterval(load, window_ === "second" ? 2000 : 30000);
  });

  onDestroy(() => {
    clearInterval(timer);
    plot?.destroy();
  });
</script>

<div class="flex flex-col gap-1">
  <div class="flex items-center justify-between">
    <span class="text-xs font-medium muted">Throughput ({unit} per {window_ === "second" ? "second, last 5 min" : "minute, last 24 h"})</span>
    <select bind:value={window_} class="text-xs">
      <option value="second">5 min</option>
      <option value="minute">24 h</option>
    </select>
  </div>
  <div bind:this={host} class="w-full"></div>
</div>
