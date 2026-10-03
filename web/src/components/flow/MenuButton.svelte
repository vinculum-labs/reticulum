<script lang="ts">
  import type { Component } from "svelte";
  import Check from "@lucide/svelte/icons/check";
  import ChevronDown from "@lucide/svelte/icons/chevron-down";
  import { ListNav } from "../../lib/listnav.svelte";
  import Popover from "../controls/Popover.svelte";

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
   * style). Arrow keys, Home, End and Enter work while it is open; Esc or a click elsewhere closes it.
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
  let trigger: HTMLButtonElement | undefined = $state();
  let list: HTMLDivElement | undefined = $state();
  let popover: Popover | undefined = $state();
  const nav = new ListNav(() => items.length, () => list);

  function pick(key: string) {
    popover?.close(true);
    onpick(key);
  }

  function toggle() {
    if (open) {
      popover?.close(false);
      return;
    }
    open = true;
    nav.set(Math.max(0, items.findIndex((i) => i.checked)));
  }

  function onkeydown(e: KeyboardEvent) {
    if (open) nav.key(e, (i) => pick(items[i].key));
    else if (e.key === "ArrowDown") {
      e.preventDefault();
      toggle();
    }
  }

  const anyChecks = $derived(items.some((i) => i.checked !== undefined));
</script>

<button
  bind:this={trigger}
  class="btn flex items-center gap-1 text-sm"
  {title}
  aria-haspopup="menu"
  aria-expanded={open}
  onclick={toggle}
  {onkeydown}
>
  <!-- Only the icon takes the color (what it handles, as on cards); the label reads like the other buttons'. -->
  {#if Icon}<span class="flex" style={color ? `color: ${color}` : ""}><Icon size={14} /></span>{/if}{label}<ChevronDown size={12} />
</button>

<Popover bind:this={popover} bind:open anchor={trigger} {width} role="menu" label={label}>
  <div bind:this={list} class="flex flex-col overflow-y-auto py-1 text-sm" {onkeydown} role="none">
    {#each items as item, i (item.key)}
      {@const ItemIcon = item.icon}
      <button
        class="list-row flex items-start gap-2 px-3 py-1 text-left"
        class:active={i === nav.active}
        role={anyChecks ? "menuitemradio" : "menuitem"}
        aria-checked={anyChecks ? !!item.checked : undefined}
        data-index={i}
        tabindex="-1"
        onpointerenter={() => (nav.active = i)}
        onclick={() => pick(item.key)}
      >
        {#if anyChecks}
          <span class="mt-0.5 w-4 shrink-0 text-accent">{#if item.checked}<Check size={14} />{/if}</span>
        {/if}
        {#if ItemIcon}<span class="mt-0.5 shrink-0" style={color ? `color: ${color}` : ""}><ItemIcon size={16} /></span>{/if}
        <span class="flex flex-col">
          <span class="font-medium">{item.label}</span>
          {#if item.hint}<span class="text-xs muted">{item.hint}</span>{/if}
        </span>
      </button>
    {/each}
  </div>
</Popover>
