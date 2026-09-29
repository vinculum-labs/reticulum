<script lang="ts">
  import { Panel, useStore, useSvelteFlow } from "@xyflow/svelte";

  /**
   * A minimap sized to the sheet's nodes only. The built-in one also fits the current view into its box, so it zooms
   * in and out while you merely pan; here the scale only changes when the sheet does, and the view rectangle moves
   * over it (cut off at the edges when it runs past them). Click or drag to pan there; scroll to zoom.
   */
  const WIDTH = 200;
  const HEIGHT = 150;
  const PADDING = 40;

  const store = $derived(useStore());
  const flow = useSvelteFlow();
  let svg: SVGSVGElement | undefined = $state();
  let dragging = false;

  const rects = $derived(
    store.nodes
      .map((n) => store.nodeLookup.get(n.id))
      .filter((n) => n && !n.hidden && n.measured.width && n.measured.height)
      .map((n) => ({
        id: n!.id,
        selected: !!n!.selected,
        x: n!.internals.positionAbsolute.x,
        y: n!.internals.positionAbsolute.y,
        width: n!.measured.width!,
        height: n!.measured.height!,
      })),
  );

  const view = $derived({
    x: -store.viewport.x / store.viewport.zoom,
    y: -store.viewport.y / store.viewport.zoom,
    width: store.width / store.viewport.zoom,
    height: store.height / store.viewport.zoom,
  });

  /** The sheet's extent (or the view, with no nodes), padded and grown to the minimap's shape. */
  const box = $derived.by(() => {
    let minX: number, minY: number, maxX: number, maxY: number;
    if (rects.length) {
      minX = Math.min(...rects.map((r) => r.x)) - PADDING;
      minY = Math.min(...rects.map((r) => r.y)) - PADDING;
      maxX = Math.max(...rects.map((r) => r.x + r.width)) + PADDING;
      maxY = Math.max(...rects.map((r) => r.y + r.height)) + PADDING;
    } else {
      minX = view.x;
      minY = view.y;
      maxX = view.x + view.width;
      maxY = view.y + view.height;
    }
    const scale = Math.max((maxX - minX) / WIDTH, (maxY - minY) / HEIGHT);
    const width = WIDTH * scale;
    const height = HEIGHT * scale;
    return { x: (minX + maxX - width) / 2, y: (minY + maxY - height) / 2, width, height };
  });

  /** Centers the canvas on the flow point under the pointer. */
  function panTo(e: PointerEvent) {
    const matrix = svg?.getScreenCTM();
    if (!svg || !matrix) return;
    const point = new DOMPoint(e.clientX, e.clientY).matrixTransform(matrix.inverse());
    flow.setCenter(point.x, point.y, { zoom: store.viewport.zoom });
  }

  function onpointerdown(e: PointerEvent) {
    dragging = true;
    svg?.setPointerCapture(e.pointerId);
    panTo(e);
  }

  function onpointermove(e: PointerEvent) {
    if (dragging) panTo(e);
  }

  function onpointerup(e: PointerEvent) {
    dragging = false;
    svg?.releasePointerCapture(e.pointerId);
  }

  function onwheel(e: WheelEvent) {
    e.preventDefault();
    if (e.deltaY < 0) flow.zoomIn();
    else flow.zoomOut();
  }
</script>

<Panel position="bottom-right" class="sheet-minimap">
  <svg
    bind:this={svg}
    width={WIDTH}
    height={HEIGHT}
    viewBox="{box.x} {box.y} {box.width} {box.height}"
    class="nowheel nopan nodrag"
    role="img"
    aria-label="Map of the sheet; click or drag to move there"
    {onpointerdown}
    {onpointermove}
    {onpointerup}
    {onwheel}
  >
    {#each rects as r (r.id)}
      <rect x={r.x} y={r.y} width={r.width} height={r.height} rx="6" class="node" class:selected={r.selected} />
    {/each}
    <rect x={view.x} y={view.y} width={view.width} height={view.height} class="view" vector-effect="non-scaling-stroke" />
  </svg>
</Panel>

<style>
  :global(.sheet-minimap) {
    border: 1px solid var(--line);
    border-radius: 4px;
    overflow: hidden;
    background: var(--panel);
    line-height: 0;
  }
  svg {
    display: block;
    cursor: pointer;
    touch-action: none;
  }
  .node {
    fill: color-mix(in srgb, var(--muted) 45%, transparent);
  }
  .node.selected {
    fill: var(--accent);
  }
  .view {
    fill: color-mix(in srgb, var(--accent) 10%, transparent);
    stroke: var(--accent);
    stroke-width: 1;
  }
</style>
