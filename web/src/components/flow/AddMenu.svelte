<script lang="ts">
  import { KIND_HINT, KIND_LABEL } from "../../lib/kinds";
  import type { NodeKind } from "../../lib/types";

  /** A toolbar button that opens a list of node kinds to add, each with its description. */
  let { label, kinds, color, onpick }: { label: string; kinds: NodeKind[]; color?: string; onpick: (kind: NodeKind) => void } = $props();

  let open = $state(false);
  let active = $state(0);
  let root: HTMLDivElement | undefined = $state();

  function pick(kind: NodeKind) {
    open = false;
    onpick(kind);
  }

  function toggle() {
    open = !open;
    active = 0;
  }

  function onkeydown(e: KeyboardEvent) {
    if (!open) return;
    if (e.key === "Escape") {
      e.preventDefault();
      open = false;
    } else if (e.key === "ArrowDown") {
      e.preventDefault();
      active = (active + 1) % kinds.length;
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      active = (active - 1 + kinds.length) % kinds.length;
    } else if (e.key === "Enter") {
      e.preventDefault();
      pick(kinds[active]);
    }
  }

  /** Closes when clicking anywhere else. */
  function onpointerdown(e: PointerEvent) {
    if (open && root && !root.contains(e.target as Node)) open = false;
  }
</script>

<svelte:window {onpointerdown} />

<div class="relative" bind:this={root}>
  <button class="btn text-sm" style={color ? `color: ${color}` : ""} aria-haspopup="menu" aria-expanded={open} onclick={toggle} {onkeydown}>
    + {label} <span class="text-xs">▾</span>
  </button>
  {#if open}
    <div
      class="absolute left-0 top-full z-20 mt-1 flex flex-col rounded-md border py-1 text-sm"
      style="min-width: 260px; background: var(--panel); border-color: var(--line); box-shadow: 0 6px 20px rgb(0 0 0 / 0.25)"
      role="menu"
    >
      {#each kinds as kind, i (kind)}
        <button
          class="menu-item flex flex-col items-start px-3 py-1 text-left"
          class:active={i === active}
          role="menuitem"
          onpointerenter={() => (active = i)}
          onclick={() => pick(kind)}
        >
          <span class="font-medium">{KIND_LABEL[kind]}</span>
          <span class="text-xs muted">{KIND_HINT[kind]}</span>
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
