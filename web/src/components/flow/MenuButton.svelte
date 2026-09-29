<script lang="ts">
  import type { Component } from "svelte";
  import Check from "@lucide/svelte/icons/check";
  import { onEscape } from "../../lib/escape";

  type IconComponent = Component<{ size?: number | string }>;

  export interface MenuItem {
    key: string;
    label: string;
    /** A line under the label. */
    hint?: string;
    icon?: IconComponent;
    /** Marked with a check (a setting's current value). */
    checked?: boolean;
  }

  /**
   * A toolbar button that opens a list to pick from: things to add (the Redstone menu) or a setting's values (wire
   * style). Arrow keys and Enter work while the button has focus; Esc or a click elsewhere closes it.
   */
  let {
    label,
    icon: Icon,
    color,
    items,
    width = 260,
    title,
    onpick,
  }: {
    label: string;
    icon?: IconComponent;
    color?: string;
    items: MenuItem[];
    width?: number;
    title?: string;
    onpick: (key: string) => void;
  } = $props();

  let open = $state(false);
  let active = $state(0);
  let root: HTMLDivElement | undefined = $state();

  function pick(key: string) {
    open = false;
    onpick(key);
  }

  function toggle() {
    open = !open;
    active = Math.max(0, items.findIndex((i) => i.checked));
  }

  function onkeydown(e: KeyboardEvent) {
    if (!open) return;
    if (e.key === "ArrowDown") {
      e.preventDefault();
      active = (active + 1) % items.length;
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      active = (active - 1 + items.length) % items.length;
    } else if (e.key === "Enter") {
      e.preventDefault();
      pick(items[active].key);
    }
  }

  // Esc closes the menu while it is open.
  $effect(() => {
    if (open) return onEscape(() => (open = false));
  });

  /** Closes when clicking anywhere else. */
  function onpointerdown(e: PointerEvent) {
    if (open && root && !root.contains(e.target as Node)) open = false;
  }

  const anyChecks = $derived(items.some((i) => i.checked !== undefined));
</script>

<svelte:window {onpointerdown} />

<div class="relative" bind:this={root}>
  <button
    class="btn flex items-center gap-1 text-sm"
    style={color ? `color: ${color}` : ""}
    {title}
    aria-haspopup="menu"
    aria-expanded={open}
    onclick={toggle}
    {onkeydown}
  >
    {#if Icon}<Icon size={14} />{/if}{label} <span class="text-xs">▾</span>
  </button>
  {#if open}
    <div
      class="absolute left-0 top-full z-20 mt-1 flex flex-col rounded-md border py-1 text-sm"
      style="min-width: {width}px; background: var(--panel); border-color: var(--line); box-shadow: 0 6px 20px rgb(0 0 0 / 0.25)"
      role="menu"
    >
      {#each items as item, i (item.key)}
        {@const ItemIcon = item.icon}
        <button
          class="menu-item flex items-start gap-2 px-3 py-1 text-left"
          class:active={i === active}
          role={anyChecks ? "menuitemradio" : "menuitem"}
          aria-checked={anyChecks ? !!item.checked : undefined}
          onpointerenter={() => (active = i)}
          onclick={() => pick(item.key)}
        >
          {#if anyChecks}
            <span class="mt-0.5 w-4 shrink-0" style="color: var(--accent)">{#if item.checked}<Check size={14} />{/if}</span>
          {/if}
          {#if ItemIcon}<span class="mt-0.5 shrink-0" style={color ? `color: ${color}` : ""}><ItemIcon size={16} /></span>{/if}
          <span class="flex flex-col">
            <span class="font-medium">{item.label}</span>
            {#if item.hint}<span class="text-xs muted">{item.hint}</span>{/if}
          </span>
        </button>
      {/each}
    </div>
  {/if}
</div>

<style>
  .menu-item.active {
    background: var(--panel-2);
  }
</style>
