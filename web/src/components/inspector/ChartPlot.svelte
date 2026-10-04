<script lang="ts" module>
  export interface PlotLine {
    label: string;
    values: number[];
    /** A CSS color or "var(--name)"; {@code shade} lightens (above 0) or darkens (below) it, -1 to 1. */
    color: string;
    shade?: number;
    dashed?: boolean;
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
  const HEIGHT = 220;
  let plot: uPlot | null = null;
  /** The lines the plot was built for: another set (or colors) builds it again; new values only update it. */
  let builtFor = "";

  function css(value: string, shade = 0): string {
    const color = value.startsWith("var(") ? getComputedStyle(document.documentElement).getPropertyValue(value.slice(4, -1)).trim() : value;
    return shade ? mix(color, shade > 0 ? "#ffffff" : "#000000", Math.abs(shade)) : color;
  }

  /** {@code a} with {@code amount} (0 to 1) of {@code b} mixed in, for "#rrggbb" colors (others as they are). */
  function mix(a: string, b: string, amount: number): string {
    const parse = (c: string) => (/^#[0-9a-f]{6}$/i.test(c) ? [1, 3, 5].map((i) => parseInt(c.slice(i, i + 2), 16)) : null);
    const x = parse(a);
    const y = parse(b);
    if (!x || !y) return a;
    return "#" + x.map((v, i) => Math.round(v + (y[i] - v) * amount).toString(16).padStart(2, "0")).join("");
  }

  $effect(() => {
    if (!host) return;
    const length = Math.max(1, ...lines.map((l) => l.values.length));
    const now = Math.floor(Date.now() / 1000);
    const xs = Array.from({ length }, (_, i) => now - (length - 1 - i) * step);
    const data: uPlot.AlignedData = [xs, ...lines.map((l) => (l.values.length === length ? l.values : Array(length).fill(0)))];
    const shape = lines.map((l) => `${l.label}|${l.color}|${l.shade ?? 0}|${!!l.dashed}`).join(";") + `|${step}`;
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
        height: HEIGHT,
        cursor: { y: false },
        legend: { show: true, live: true },
        scales: { x: { time: true }, y: { range: (_u, _min, max) => [0, Math.max(1, max * 1.1)] } },
        axes: [axis, { ...axis, size: 52 }],
        series: [{}, ...lines.map((l) => ({ label: l.label, stroke: css(l.color, l.shade), width: 1.5, dash: l.dashed ? [5, 4] : undefined, value: (_u: uPlot, v: number | null) => (v == null ? "–" : format(v)) }))],
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

  // Its card is as wide as the grid makes it: the plot follows.
  $effect(() => {
    if (!host) return;
    const observer = new ResizeObserver(() => {
      if (plot && host && Math.abs(plot.width - host.clientWidth) > 1) plot.setSize({ width: host.clientWidth, height: HEIGHT });
    });
    observer.observe(host);
    return () => observer.disconnect();
  });

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
