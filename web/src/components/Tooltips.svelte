<script lang="ts">
  import { onMount } from "svelte";

  /**
   * The editor's tooltips, in place of the browser's own: those look out of place, and when the game draws the editor
   * into its frame they are separate windows that may not show at all. Any element with a `title` gets one: on hover
   * (after a short wait) or keyboard focus, below it (above when there is no room), kept on screen, in the top layer
   * so it shows over dialogs and dropdowns. The `title` is moved to `data-tip` as it is pointed at, so the browser
   * never shows its own; an element with no other name keeps the text as its spoken name.
   */

  const DELAY = 450;
  const GAP = 6;

  let tip: HTMLDivElement | undefined = $state();
  let text = $state("");
  let shownFor: HTMLElement | null = null;
  let timer: ReturnType<typeof setTimeout> | undefined;

  /** The nearest element with a tip, its `title` moved aside (a later `title` from the page replaces the old tip). */
  function holder(start: EventTarget | null): HTMLElement | null {
    let el = start instanceof Element ? (start.closest("[title], [data-tip]") as HTMLElement | null) : null;
    if (!el) return null;
    const title = el.getAttribute("title");
    if (title !== null) {
      el.removeAttribute("title");
      if (title.trim()) {
        el.dataset.tip = title;
        if (!el.getAttribute("aria-label") && !el.textContent?.trim()) el.setAttribute("aria-label", title);
      } else {
        delete el.dataset.tip;
      }
    }
    return el.dataset.tip ? el : null;
  }

  function hide() {
    clearTimeout(timer);
    shownFor = null;
    if (tip?.matches(":popover-open")) tip.hidePopover();
  }

  function schedule(el: HTMLElement, delay: number) {
    clearTimeout(timer);
    timer = setTimeout(() => show(el), delay);
  }

  async function show(el: HTMLElement) {
    if (!tip || !el.isConnected || !el.dataset.tip) return;
    shownFor = el;
    text = el.dataset.tip;
    if (!tip.matches(":popover-open")) tip.showPopover();
    // Placed once laid out with its text: centered under the element, or over it, inside the window.
    await Promise.resolve();
    const r = el.getBoundingClientRect();
    const w = tip.offsetWidth;
    const h = tip.offsetHeight;
    const below = r.bottom + GAP + h <= window.innerHeight - 4 || r.top - GAP - h < 4;
    tip.style.left = `${Math.max(4, Math.min(r.left + r.width / 2 - w / 2, window.innerWidth - w - 4))}px`;
    tip.style.top = `${below ? r.bottom + GAP : r.top - GAP - h}px`;
  }

  onMount(() => {
    const over = (e: PointerEvent) => {
      const el = holder(e.target);
      if (el === shownFor) return;
      hide();
      if (el) schedule(el, DELAY);
    };
    const out = (e: PointerEvent) => {
      const el = holder(e.target);
      if (el && !el.contains(e.relatedTarget as Node | null)) hide();
    };
    const focus = (e: FocusEvent) => {
      const el = holder(e.target);
      if (el && (e.target as Element).matches(":focus-visible")) schedule(el, DELAY);
    };
    const blur = () => hide();
    const opts = { capture: true };
    document.addEventListener("pointerover", over, opts);
    document.addEventListener("pointerout", out, opts);
    document.addEventListener("focusin", focus, opts);
    document.addEventListener("focusout", blur, opts);
    for (const type of ["pointerdown", "wheel", "keydown", "scroll"]) document.addEventListener(type, hide, opts);
    window.addEventListener("blur", hide);
    // A title the page sets or changes later (a re-render) is moved aside at once, and an open tip follows it.
    const titles = new MutationObserver((records) => {
      for (const r of records) {
        if (r.target instanceof HTMLElement && r.target.hasAttribute("title")) {
          holder(r.target);
          if (r.target === shownFor) text = r.target.dataset.tip ?? "";
        }
      }
    });
    titles.observe(document.body, { attributes: true, attributeFilter: ["title"], subtree: true });
    // A title taken away (Svelte removes title={undefined}) after it moved into data-tip: the attribute is already
    // gone, so nothing is observed. Its tip goes with it here instead.
    const removeAttribute = Element.prototype.removeAttribute;
    Element.prototype.removeAttribute = function (name: string) {
      if (name === "title" && this instanceof HTMLElement && !this.hasAttribute("title") && this.dataset.tip !== undefined) {
        delete this.dataset.tip;
        if (this === shownFor) hide();
      }
      return removeAttribute.call(this, name);
    };
    return () => {
      Element.prototype.removeAttribute = removeAttribute;
      titles.disconnect();
      document.removeEventListener("pointerover", over, opts);
      document.removeEventListener("pointerout", out, opts);
      document.removeEventListener("focusin", focus, opts);
      document.removeEventListener("focusout", blur, opts);
      for (const type of ["pointerdown", "wheel", "keydown", "scroll"]) document.removeEventListener(type, hide, opts);
      window.removeEventListener("blur", hide);
      clearTimeout(timer);
    };
  });
</script>

<div bind:this={tip} popover="manual" class="tooltip" role="tooltip">{text}</div>

<style>
  .tooltip {
    position: fixed;
    inset: auto;
    margin: 0;
    max-width: 300px;
    padding: 4px 8px;
    border: 1px solid var(--line);
    border-radius: 6px;
    background: var(--panel-2);
    color: var(--ink);
    font-size: 12px;
    line-height: 16px;
    white-space: pre-line;
    overflow-wrap: anywhere;
    pointer-events: none;
    box-shadow: 0 4px 16px rgb(0 0 0 / 0.3);
  }
</style>
