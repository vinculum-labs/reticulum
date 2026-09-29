<script lang="ts">
  /*
   * Svelte Flow's minimap (MIT, xyflow), with one change: it fits the nodes and the view when you zoom or the sheet
   * changes, but not while you only pan. The original re-fits on every pan, so it zooms in and out as you drag the
   * canvas around. Panning past what it shows lets the view rectangle run off its edge until the next zoom.
   */
  import { untrack } from "svelte";
  import { Panel, useStore } from "@xyflow/svelte";
  import { XYMinimap, getBoundsOfRects, getInternalNodesBounds, nodeHasDimensions, type Rect } from "@xyflow/system";

  const WIDTH = 200;
  const HEIGHT = 150;

  const store = $derived(useStore());

  const viewBB = $derived({
    x: -store.viewport.x / store.viewport.zoom,
    y: -store.viewport.y / store.viewport.zoom,
    width: store.width / store.viewport.zoom,
    height: store.height / store.viewport.zoom,
  });

  const nodeBounds = $derived.by((): Rect | null =>
    store.nodes.some((n) => !n.hidden) ? getInternalNodesBounds(store.nodeLookup, { filter: (n) => !n.hidden }) : null,
  );

  /** What the minimap fits: set again when the zoom, the canvas size or the nodes change, not on a pan. */
  let boundingRect = $state<Rect>({ x: 0, y: 0, width: 1, height: 1 });
  $effect(() => {
    const nodes = nodeBounds;
    void store.viewport.zoom;
    void store.width;
    void store.height;
    const view = untrack(() => viewBB);
    boundingRect = nodes ? getBoundsOfRects(nodes, view) : view;
  });

  const viewScale = $derived(Math.max(boundingRect.width / WIDTH, boundingRect.height / HEIGHT));
  const viewWidth = $derived(viewScale * WIDTH);
  const viewHeight = $derived(viewScale * HEIGHT);
  const offset = $derived(5 * viewScale);
  const x = $derived(boundingRect.x - (viewWidth - boundingRect.width) / 2 - offset);
  const y = $derived(boundingRect.y - (viewHeight - boundingRect.height) / 2 - offset);
  const boxWidth = $derived(viewWidth + offset * 2);
  const boxHeight = $derived(viewHeight + offset * 2);

  /** Dragging and scrolling on the minimap, exactly as the original does. */
  function interactive(dom: SVGSVGElement) {
    const minimap = XYMinimap({
      domNode: dom,
      panZoom: store.panZoom!,
      getTransform: () => [store.viewport.x, store.viewport.y, store.viewport.zoom],
      getViewScale: () => viewScale,
    });
    $effect(() => {
      minimap.update({
        translateExtent: store.translateExtent,
        width: store.width,
        height: store.height,
        pannable: true,
        zoomable: true,
      });
    });
    return { destroy: () => minimap.destroy() };
  }
</script>

<Panel position="bottom-right" class="svelte-flow__minimap sheet-minimap">
  {#if store.panZoom}
    <svg
      width={WIDTH}
      height={HEIGHT}
      viewBox="{x} {y} {boxWidth} {boxHeight}"
      class="svelte-flow__minimap-svg"
      role="img"
      aria-label="Map of the sheet"
      use:interactive
    >
      {#each store.nodes as userNode (userNode.id)}
        {@const node = store.nodeLookup.get(userNode.id)}
        {#if node && nodeHasDimensions(node) && !node.hidden}
          <rect
            class="svelte-flow__minimap-node"
            class:selected={node.selected}
            x={node.internals.positionAbsolute.x}
            y={node.internals.positionAbsolute.y}
            width={node.measured.width ?? node.width ?? 0}
            height={node.measured.height ?? node.height ?? 0}
            rx="5"
            ry="5"
          />
        {/if}
      {/each}
      <!-- Everything but the view, dimmed; then the view's own outline. -->
      <path
        class="svelte-flow__minimap-mask"
        d="M{x - offset},{y - offset}h{boxWidth + offset * 2}v{boxHeight + offset * 2}h{-boxWidth - offset * 2}z
        M{viewBB.x},{viewBB.y}h{viewBB.width}v{viewBB.height}h{-viewBB.width}z"
        fill-rule="evenodd"
        pointer-events="none"
      />
      <rect x={viewBB.x} y={viewBB.y} width={viewBB.width} height={viewBB.height} class="view" vector-effect="non-scaling-stroke" pointer-events="none" />
    </svg>
  {/if}
</Panel>

<style>
  :global(.sheet-minimap) {
    border: 1px solid var(--line);
    border-radius: 4px;
    overflow: hidden;
    background: var(--panel);
    line-height: 0;
    /* Theme colors instead of Svelte Flow's light defaults (a light-gray mask looked like a thick border in dark mode). */
    --xy-minimap-mask-background-color: color-mix(in srgb, var(--panel-2) 70%, transparent);
    --xy-minimap-mask-stroke-color: transparent;
    --xy-minimap-node-background-color: color-mix(in srgb, var(--muted) 45%, transparent);
  }
  :global(.sheet-minimap .svelte-flow__minimap-node.selected) {
    fill: var(--accent);
  }
  .view {
    fill: none;
    stroke: var(--accent);
    stroke-width: 1;
  }
</style>
