<script lang="ts" module>
  /** The size of the large dialogs (the filter picker, pasting a sheet): most of the window, up to 760 × 640. */
  export const LARGE_WIDTH = "min(760px, 80vw)";
  export const LARGE_HEIGHT = "min(640px, 80vh)";
</script>

<script lang="ts">
  import { untrack, type Snippet } from "svelte";
  import { onEscape } from "../lib/escape";
  import { modals } from "../lib/dialog.svelte";

  /**
   * The frame of every dialog: a native <dialog> shown modally while this is mounted, so the page behind it is inert
   * and Tab stays inside. Esc (through the page's Esc stack) and a click on the backdrop call {@code onclose}.
   * Dialogs that take input can be resized from every edge and corner.
   */
  let {
    onclose,
    width,
    height,
    resizable = false,
    minWidth = 320,
    minHeight = 220,
    label,
    title,
    footer,
    children,
  }: {
    onclose: () => void;
    /** Starting width: CSS pixels, or any CSS length (e.g. LARGE_WIDTH, a share of the window). */
    width: number | string;
    /** Starting height, likewise; fits the content when absent. */
    height?: number | string;
    resizable?: boolean;
    minWidth?: number;
    minHeight?: number;
    label?: string;
    /** Shown at the top, and names the dialog. */
    title?: string;
    /** The buttons, at the bottom right (Cancel first, the main action last). */
    footer?: Snippet;
    children: Snippet;
  } = $props();

  let element: HTMLDialogElement | undefined = $state();
  /** The size the dialog was dragged to; null until it is resized. */
  let size = $state<{ width: number; height: number } | null>(null);

  $effect(() => {
    if (!element) return;
    if (!element.open) element.showModal();
    const release = onEscape(() => onclose());
    // Untracked: the count is written here, not depended on.
    untrack(() => modals.open++);
    return () => {
      release();
      untrack(() => modals.open--);
      if (element?.open) element.close();
    };
  });

  /** Whether the current press began on the backdrop (the dialog element itself, outside its box). */
  let pressedBackdrop = false;

  function onpointerdown(e: PointerEvent) {
    pressedBackdrop = e.target === element;
  }

  /**
   * A click on the backdrop closes, but only when the press began there too: a drag that starts inside (resizing,
   * selecting text) and ends outside is also reported as a click on the dialog element.
   */
  function onclick(e: MouseEvent) {
    if (pressedBackdrop && e.target === element) onclose();
    pressedBackdrop = false;
  }

  /** Handles: which way each moves the edges it sits on (-1 left/top, 1 right/bottom, 0 neither). */
  const HANDLES = [
    { x: 0, y: -1, cursor: "ns-resize", style: "top: 0; left: 8px; right: 8px; height: 6px" },
    { x: 0, y: 1, cursor: "ns-resize", style: "bottom: 0; left: 8px; right: 8px; height: 6px" },
    { x: -1, y: 0, cursor: "ew-resize", style: "left: 0; top: 8px; bottom: 8px; width: 6px" },
    { x: 1, y: 0, cursor: "ew-resize", style: "right: 0; top: 8px; bottom: 8px; width: 6px" },
    { x: -1, y: -1, cursor: "nwse-resize", style: "top: 0; left: 0; width: 10px; height: 10px" },
    { x: 1, y: 1, cursor: "nwse-resize", style: "bottom: 0; right: 0; width: 10px; height: 10px" },
    { x: 1, y: -1, cursor: "nesw-resize", style: "top: 0; right: 0; width: 10px; height: 10px" },
    { x: -1, y: 1, cursor: "nesw-resize", style: "bottom: 0; left: 0; width: 10px; height: 10px" },
  ];

  /**
   * Drags an edge or corner. The dialog stays centered, so it grows by twice the pointer's movement: the dragged edge
   * follows the pointer and the opposite one moves to match.
   */
  function startResize(e: PointerEvent, handle: (typeof HANDLES)[number]) {
    if (!element || e.button !== 0) return;
    e.preventDefault();
    const target = e.currentTarget as HTMLElement;
    target.setPointerCapture(e.pointerId);
    const rect = element.getBoundingClientRect();
    const start = { x: e.clientX, y: e.clientY, width: rect.width, height: rect.height };
    const move = (m: PointerEvent) => {
      const w = start.width + 2 * handle.x * (m.clientX - start.x);
      const h = start.height + 2 * handle.y * (m.clientY - start.y);
      size = {
        width: Math.round(Math.min(window.innerWidth - 32, Math.max(minWidth, w))),
        height: Math.round(Math.min(window.innerHeight - 32, Math.max(minHeight, h))),
      };
    };
    const end = () => {
      target.removeEventListener("pointermove", move);
      target.removeEventListener("pointerup", end);
      target.removeEventListener("pointercancel", end);
    };
    target.addEventListener("pointermove", move);
    target.addEventListener("pointerup", end);
    target.addEventListener("pointercancel", end);
  }

  const css = (length: number | string) => (typeof length === "number" ? `${length}px` : length);
  const shownWidth = $derived(size ? `${size.width}px` : css(width));
  const shownHeight = $derived(size ? `${size.height}px` : height === undefined ? undefined : css(height));
</script>

<!-- Esc goes through the page's Esc stack; the dialog's own cancel is only a fallback. -->
<dialog
  bind:this={element}
  class="m-auto max-h-[calc(100vh-32px)] max-w-[calc(100vw-32px)] overflow-hidden rounded-lg border p-0"
  style="width: {shownWidth}; {shownHeight === undefined ? '' : `height: ${shownHeight};`} background: var(--panel); color: var(--ink); border-color: var(--line); box-shadow: 0 12px 40px rgb(0 0 0 / 0.35)"
  aria-label={label ?? title}
  oncancel={(e) => (e.preventDefault(), onclose())}
  {onpointerdown}
  {onclick}
>
  <div class="flex flex-col gap-3 p-4 {shownHeight === undefined ? '' : 'h-full'}">
    {#if title}<h2 class="text-base font-semibold">{title}</h2>{/if}
    <div class="flex min-h-0 flex-1 flex-col gap-3">{@render children()}</div>
    {#if footer}<div class="flex items-center justify-end gap-2">{@render footer()}</div>{/if}
  </div>
  {#if resizable}
    {#each HANDLES as handle (handle.style)}
      <div class="absolute" style="{handle.style}; cursor: {handle.cursor}" aria-hidden="true" onpointerdown={(e) => startResize(e, handle)}></div>
    {/each}
  {/if}
</dialog>

<style>
  dialog::backdrop {
    background: rgb(0 0 0 / 0.45);
  }
</style>
