<script lang="ts">
  import { tick } from "svelte";
  import { answerDialog, cancelDialog, dialogs } from "../lib/dialog.svelte";
  import { onEscape } from "../lib/escape";

  /**
   * The one dialog the editor shows (lib/dialog.svelte.ts opens it). A native <dialog> shown modally, so the page
   * behind it is inert and Tab stays inside.
   */
  let element: HTMLDialogElement | undefined = $state();
  let field: HTMLInputElement | HTMLTextAreaElement | undefined = $state();
  let cancelButton: HTMLButtonElement | undefined = $state();
  let confirmButton: HTMLButtonElement | undefined = $state();
  let value = $state("");

  const open = $derived(dialogs.open);
  /** A dialog with a large text box can be resized from its edges and corners. */
  const resizable = $derived(open?.kind === "prompt" && !!open.multiline);
  /** The size the dialog was dragged to, in CSS pixels; null until it is resized. */
  let size: { width: number; height: number } | null = $state(null);

  $effect(() => {
    if (!open || !element) return;
    value = open.kind === "prompt" ? (open.value ?? "") : "";
    size = null;
    if (!element.open) element.showModal();
    // A destructive question starts on Cancel, so Enter doesn't confirm it by accident.
    tick().then(() => (open.kind === "prompt" ? field : open.danger ? cancelButton : confirmButton)?.focus());
    const release = onEscape(cancelDialog);
    return () => {
      release();
      if (element?.open) element.close();
    };
  });

  function submit(e: Event) {
    e.preventDefault();
    if (!open) return;
    answerDialog(open.kind === "prompt" ? value : true);
  }

  function onfieldkeydown(e: KeyboardEvent) {
    if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) submit(e);
  }

  const MIN_WIDTH = 320;
  const MIN_HEIGHT = 220;
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
      const width = start.width + 2 * handle.x * (m.clientX - start.x);
      const height = start.height + 2 * handle.y * (m.clientY - start.y);
      size = {
        width: Math.round(Math.min(window.innerWidth - 32, Math.max(MIN_WIDTH, width))),
        height: Math.round(Math.min(window.innerHeight - 32, Math.max(MIN_HEIGHT, height))),
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

  /** Whether the current press began on the backdrop (the dialog element itself, outside its box). */
  let pressedBackdrop = false;

  function onpointerdown(e: PointerEvent) {
    pressedBackdrop = e.target === element;
  }

  /**
   * A click on the backdrop cancels, but only when the press began there too: a drag that starts inside (resizing the
   * text box, selecting text) and ends outside is also reported as a click on the dialog element.
   */
  function onclick(e: MouseEvent) {
    if (pressedBackdrop && e.target === element) cancelDialog();
    pressedBackdrop = false;
  }
</script>

<!-- Esc goes through the page's Esc stack; the dialog's own cancel is only a fallback. -->
<dialog
  bind:this={element}
  class="m-auto max-h-[calc(100vh-32px)] max-w-[calc(100vw-32px)] rounded-lg border p-0 {size ? '' : resizable ? 'w-[560px]' : 'w-[440px]'}"
  style="background: var(--panel); color: var(--ink); border-color: var(--line); box-shadow: 0 12px 40px rgb(0 0 0 / 0.35);{size
    ? ` width: ${size.width}px; height: ${size.height}px`
    : ''}"
  oncancel={(e) => (e.preventDefault(), cancelDialog())}
  {onpointerdown}
  {onclick}
>
  {#if open}
    <form class="flex flex-col gap-3 p-4 {size ? 'h-full' : ''}" onsubmit={submit}>
      <h2 class="text-base font-semibold">{open.title}</h2>
      {#if open.message}<p class="text-sm muted">{open.message}</p>{/if}
      {#if open.kind === "prompt"}
        {#if open.multiline}
          <textarea
            bind:this={field}
            bind:value
            class="w-full resize-none font-mono text-xs {size ? 'min-h-0 flex-1' : 'h-48'}"
            placeholder={open.placeholder}
            spellcheck="false"
            onkeydown={onfieldkeydown}
          ></textarea>
        {:else}
          <input bind:this={field} bind:value class="w-full" placeholder={open.placeholder} />
        {/if}
      {/if}
      <div class="flex justify-end gap-2">
        {#if open.kind === "prompt" && open.multiline}<span class="mr-auto self-center text-xs muted">Ctrl+Enter to confirm</span>{/if}
        <button bind:this={cancelButton} type="button" class="btn" onclick={cancelDialog}>Cancel</button>
        <button bind:this={confirmButton} type="submit" class="btn {open.kind === 'confirm' && open.danger ? 'btn-destructive' : 'btn-primary'}">
          {open.confirm ?? "OK"}
        </button>
      </div>
    </form>
    {#if resizable}
      {#each HANDLES as handle (handle.style)}
        <div class="absolute" style="{handle.style}; cursor: {handle.cursor}" aria-hidden="true" onpointerdown={(e) => startResize(e, handle)}></div>
      {/each}
    {/if}
  {/if}
</dialog>

<style>
  dialog::backdrop {
    background: rgb(0 0 0 / 0.45);
  }
</style>
