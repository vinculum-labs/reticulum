<script lang="ts" module>
  export interface PlotLine {
    label: string;
    values: number[];
    color: string;
  }
</script>

<script lang="ts">
  import { onDestroy } from "svelte";
  import uPlot from "uplot";
  import "uplot/dist/uPlot.min.css";

  /**
   * Lines over time, newest at the right, one value per {@code step} seconds. Its legend shows each line's value under
   * the cursor; clicking a name hides or shows that line.
   */
  let { lines, step }: { lines: PlotLine[]; step: number } = $props();

  let host: HTMLDivElement | undefined = $state();
  let plot: uPlot | null = null;
  /** The lines the plot was built for: another set (or colors) builds it again; new values only update it. */
  let builtFor = "";

  function css(value: string): string {
    if (!value.startsWith("var(")) return value;
    return getComputedStyle(document.documentElement).getPropertyValue(value.slice(4, -1)).trim();
  }

  $effect(() => {
    if (!host) return;
    const length = Math.max(1, ...lines.map((l) => l.values.length));
    const now = Math.floor(Date.now() / 1000);
    const xs = Array.from({ length }, (_, i) => now - (length - 1 - i) * step);
    const data: uPlot.AlignedData = [xs, ...lines.map((l) => (l.values.length === length ? l.values : Array(length).fill(0)))];
    const shape = lines.map((l) => `${l.label}|${l.color}`).join(";") + `|${step}`;
    if (plot && shape === builtFor) {
      plot.setData(data);
      return;
    }
    plot?.destroy();
    builtFor = shape;
    const axis = { stroke: css("var(--muted)"), grid: { stroke: css("var(--grid)"), width: 1 }, ticks: { stroke: css("var(--grid)") } };
    plot = new uPlot(
      {
        width: host.clientWidth,
        height: 150,
        cursor: { y: false },
        legend: { show: true, live: true },
        scales: { x: { time: true }, y: { range: (_u, _min, max) => [0, Math.max(1, max * 1.1)] } },
        axes: [axis, { ...axis, size: 52 }],
        series: [{}, ...lines.map((l) => ({ label: l.label, stroke: css(l.color), width: 1.5, value: (_u: uPlot, v: number | null) => (v == null ? "–" : format(v)) }))],
      },
      data,
      host,
    );
  });

  function format(v: number): string {
    if (v >= 1_000_000) return `${(v / 1_000_000).toFixed(1)}M`;
    if (v >= 10_000) return `${(v / 1000).toFixed(1)}k`;
    return v >= 100 ? v.toFixed(0) : v.toFixed(2);
  }

  onDestroy(() => plot?.destroy());
</script>

<div bind:this={host} class="chart w-full"></div>

<style>
  .chart :global(.u-legend) {
    font-size: 11px;
    text-align: left;
  }
  .chart :global(.u-legend .u-series) {
    padding: 0 4px;
  }
</style>
