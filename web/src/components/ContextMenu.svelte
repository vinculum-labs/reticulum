<script lang="ts">
  import Check from "@lucide/svelte/icons/check";
  import ChevronRight from "@lucide/svelte/icons/chevron-right";
  import { onMount, tick } from "svelte";
  import { closeMenu, menu, shown, type MenuEntry, type MenuItem } from "../lib/menu.svelte";
  import { ListNav } from "../lib/listnav.svelte";
  import Self from "./ContextMenu.svelte";

  /**
   * A right-click menu: at the pointer (or beside its parent item, for a submenu), kept on screen, in the top layer.
   * Arrow keys move, Enter or a click picks, → opens a submenu and ← or Esc goes back; a click outside, scrolling or
   * leaving the window closes it. With no props it draws the menu that is open; a submenu passes its own.
   */
  let {
    items: given,
    at,
    onback,
  }: {
    items?: MenuEntry[];
    /** A submenu: the parent item's box, to open beside it. */
    at?: DOMRect;
    onback?: () => void;
  } = $props();

  const root = $derived(given === undefined);
  const items = $derived(shown(given ?? menu.open?.items ?? []));
  const open = $derived(root ? !!menu.open : true);

  let element: HTMLDivElement | undefined = $state();
  let sub = $state<{ index: number; at: DOMRect } | null>(null);

  function selectable(i: number): boolean {
    const item = items[i];
    return !!item && item !== "-" && !item.disabled;
  }

  // Separators and disabled items are skipped; nothing is active until a key or the pointer picks a row.
  const nav = new ListNav(() => items.length, () => element, { selectable });

  function openSub(i: number) {
    const row = element?.querySelector(`[data-index="${i}"]`);
    if (row) sub = { index: i, at: row.getBoundingClientRect() };
  }

  function pick(item: MenuItem, i: number) {
    if (item.disabled) return;
    if (item.items) return openSub(i);
    closeMenu();
    item.run?.();
  }

  function onkeydown(e: KeyboardEvent) {
    if (sub) return;
    const item = items[nav.active];
    if (e.key === " " || (e.key === "ArrowRight" && item && item !== "-" && item.items)) {
      if (item && item !== "-") pick(item, nav.active);
    } else if (e.key === "Escape") (root ? closeMenu() : onback?.());
    else if (e.key === "ArrowLeft" && !root) onback?.();
    else if (!nav.key(e, (i) => pick(items[i] as MenuItem, i))) return;
    e.preventDefault();
    e.stopPropagation();
  }

  /** Focus leaving every menu (Tab, or a click into the page) closes them. */
  function onfocusout(e: FocusEvent) {
    const to = e.relatedTarget;
    if (root && !(to instanceof Node && [...document.querySelectorAll(".context-menu")].some((m) => m.contains(to)))) closeMenu(false);
  }

  // Shown in the top layer once drawn, then placed by its real size: at the pointer, or to the side of its parent
  // item (the other side when there is no room), inside the window.
  $effect(() => {
    if (!open || !element) return;
    const el = element;
    void items;
    if (!el.matches(":popover-open")) el.showPopover();
    const w = el.offsetWidth;
    const h = el.offsetHeight;
    let x: number;
    let y: number;
    if (at) {
      x = at.right + w <= window.innerWidth - 4 ? at.right - 2 : at.left - w + 2;
      y = at.top - 5;
    } else {
      x = menu.open!.x;
      y = menu.open!.y;
    }
    el.style.left = `${Math.max(4, Math.min(x, window.innerWidth - w - 4))}px`;
    el.style.top = `${Math.max(4, Math.min(y, window.innerHeight - h - 4))}px`;
    nav.active = -1;
    sub = null;
    tick().then(() => el.focus());
  });

  onMount(() => {
    if (!root) return;
    const away = (e: Event) => {
      if (!(e.target instanceof Node) || !document.querySelector(".context-menu")?.parentElement) return;
      if (![...document.querySelectorAll(".context-menu")].some((m) => m.contains(e.target as Node))) closeMenu(false);
    };
    const close = () => closeMenu(false);
    document.addEventListener("pointerdown", away, true);
    document.addEventListener("wheel", away, true);
    window.addEventListener("blur", close);
    window.addEventListener("resize", close);
    return () => {
      document.removeEventListener("pointerdown", away, true);
      document.removeEventListener("wheel", away, true);
      window.removeEventListener("blur", close);
      window.removeEventListener("resize", close);
    };
  });
</script>

{#if open && items.length}
  <!-- svelte-ignore a11y_no_noninteractive_tabindex -->
  <div bind:this={element} popover="manual" class="context-menu flex flex-col py-1 text-sm" role="menu" tabindex="-1" {onkeydown} {onfocusout}>
    {#each items as item, i (i)}
      {#if item === "-"}
        <div class="my-1 h-px bg-line" role="separator"></div>
      {:else}
        {@const ItemIcon = item.icon}
        <button
          type="button"
          class="list-row flex items-center gap-2 px-3 py-1 text-left"
          class:active={i === nav.active || sub?.index === i}
          class:danger={item.danger}
          role={item.checked !== undefined ? "menuitemcheckbox" : "menuitem"}
          aria-checked={item.checked !== undefined ? item.checked : undefined}
          aria-haspopup={item.items ? "menu" : undefined}
          aria-disabled={item.disabled}
          disabled={item.disabled}
          data-index={i}
          tabindex="-1"
          onpointerenter={() => {
            nav.active = i;
            if (item.items) openSub(i);
            else sub = null;
          }}
          onclick={() => pick(item, i)}
        >
          <span class="flex w-4 shrink-0 justify-center">
            {#if item.checked}<Check size={14} />{:else if ItemIcon}<ItemIcon size={15} />{/if}
          </span>
          <span class="flex min-w-0 flex-1 flex-col">
            <span class="trim-text truncate">{item.label}</span>
            {#if item.hint}<span class="text-xs muted">{item.hint}</span>{/if}
          </span>
          {#if item.shortcut}<span class="ml-4 shrink-0 text-xs muted">{item.shortcut}</span>{/if}
          {#if item.items}<ChevronRight size={14} class="shrink-0 muted" />{/if}
        </button>
      {/if}
    {/each}
  </div>
  {#if sub}
    {@const parent = items[sub.index]}
    {#if parent && parent !== "-" && parent.items}
      <Self items={parent.items} at={sub.at} onback={() => {
        const i = sub!.index;
        sub = null;
        nav.active = i;
        element?.focus();
      }} />
    {/if}
  {/if}
{/if}

<style>
  .context-menu {
    position: fixed;
    inset: auto;
    margin: 0;
    min-width: 200px;
    max-width: 320px;
    max-height: calc(100vh - 8px);
    overflow-y: auto;
    border: 1px solid var(--line);
    border-radius: 8px;
    background: var(--panel);
    color: var(--ink);
    box-shadow: 0 8px 28px rgb(0 0 0 / 0.35);
    outline: none;
  }
  .context-menu button:disabled {
    opacity: 0.45;
    cursor: default;
  }
  .context-menu .danger {
    color: var(--bad);
  }
</style>
