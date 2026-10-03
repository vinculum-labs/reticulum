<script lang="ts">
  import { tick, type Snippet } from "svelte";
  import { onEscape } from "../../lib/escape";

  /**
   * A box that opens under (or, without room, above) its trigger: a menu, a picker's list. It sits in the page's top
   * layer (the browser's popover), so a scrolling panel doesn't clip it. Esc, a press outside, or focus moving
   * elsewhere closes it, and focus goes back to the trigger.
   */
  let {
    open = $bindable(false),
    anchor,
    width,
    label,
    role = "dialog",
    children,
  }: {
    open: boolean;
    /** The trigger it opens from. */
    anchor: HTMLElement | undefined;
    /** At least this wide (px); otherwise as wide as the trigger. */
    width?: number;
    label?: string;
    role?: string;
    children: Snippet;
  } = $props();

  let element: HTMLDivElement | undefined = $state();
  let position = $state({ left: 0, top: 0, width: 0, maxHeight: 320, above: false });

  function place() {
    if (!anchor) return;
    const rect = anchor.getBoundingClientRect();
    const below = window.innerHeight - rect.bottom - 8;
    const aboveRoom = rect.top - 8;
    const above = below < 200 && aboveRoom > below;
    const w = Math.max(rect.width, width ?? 0);
    // Kept on screen by the width it really takes (its longest option may make it wider than the box it opens from).
    const span = Math.max(w, element?.offsetWidth ?? 0);
    position = {
      left: Math.max(8, Math.min(rect.left, window.innerWidth - span - 8)),
      top: above ? rect.top - 4 : rect.bottom + 4,
      width: w,
      maxHeight: Math.max(120, Math.min(480, above ? aboveRoom : below)),
      above,
    };
  }

  $effect(() => {
    if (!open || !element) return;
    place();
    element.showPopover();
    // Again now that it is laid out, with its real width.
    place();
    const release = onEscape(() => close(true));
    const onpointerdown = (e: PointerEvent) => {
      const target = e.target as Node;
      if (!element?.contains(target) && !anchor?.contains(target)) close(false);
    };
    const reposition = () => place();
    window.addEventListener("pointerdown", onpointerdown, true);
    window.addEventListener("resize", reposition);
    window.addEventListener("scroll", reposition, true);
    return () => {
      release();
      window.removeEventListener("pointerdown", onpointerdown, true);
      window.removeEventListener("resize", reposition);
      window.removeEventListener("scroll", reposition, true);
      if (element?.matches(":popover-open")) element.hidePopover();
    };
  });

  /** Closes; {@code refocus} gives the keyboard back to the trigger (Esc, a pick), not when clicking elsewhere. */
  export function close(refocus: boolean) {
    if (!open) return;
    open = false;
    if (refocus) tick().then(() => anchor?.focus());
  }

  function onfocusout(e: FocusEvent) {
    const next = e.relatedTarget as Node | null;
    if (next && !element?.contains(next) && !anchor?.contains(next)) close(false);
  }
</script>

{#if open}
  <div
    bind:this={element}
    popover="manual"
    class="popover m-0 flex flex-col overflow-hidden p-0"
    {role}
    aria-label={label}
    style="left: {position.left}px; {position.above ? `bottom: ${window.innerHeight - position.top}px` : `top: ${position.top}px`}; min-width: {position.width}px; max-width: calc(100vw - 16px); max-height: {position.maxHeight}px"
    {onfocusout}
  >
    {@render children()}
  </div>
{/if}

<style>
  .popover {
    position: fixed;
    inset: unset;
    background: var(--panel);
    color: var(--ink);
    border: 1px solid var(--line);
    border-radius: 6px;
    box-shadow: 0 6px 20px rgb(0 0 0 / 0.25);
  }
</style>
