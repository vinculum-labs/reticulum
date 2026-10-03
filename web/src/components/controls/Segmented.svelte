<script lang="ts" generics="T extends string">
  import type { Component } from "svelte";

  /** One of a few choices, all in view: joined buttons, the chosen one filled. Arrow keys move the choice. */
  let {
    options,
    value,
    onchange,
    disabled = false,
    label,
    size = "sm",
  }: {
    options: { value: T; label: string; title?: string; icon?: Component<{ size?: number | string }> }[];
    value: T;
    onchange: (value: T) => void;
    disabled?: boolean;
    /** Names the group for screen readers. */
    label?: string;
    size?: "xs" | "sm";
  } = $props();

  function onkeydown(e: KeyboardEvent) {
    const i = options.findIndex((o) => o.value === value);
    const step = e.key === "ArrowRight" || e.key === "ArrowDown" ? 1 : e.key === "ArrowLeft" || e.key === "ArrowUp" ? -1 : 0;
    if (!step || disabled) return;
    e.preventDefault();
    onchange(options[(i + step + options.length) % options.length].value);
  }
</script>

<div class="segmented inline-flex shrink-0" role="radiogroup" aria-label={label} tabindex={disabled ? -1 : 0} {onkeydown}>
  {#each options as option (option.value)}
    {@const Icon = option.icon}
    <button
      type="button"
      role="radio"
      aria-checked={option.value === value}
      class="btn flex items-center gap-1 {size === 'xs' ? 'text-xs' : 'text-sm'} {option.label ? '' : 'px-2'}"
      class:btn-primary={option.value === value}
      tabindex="-1"
      title={option.title}
      aria-label={option.label ? undefined : option.title}
      {disabled}
      onclick={() => option.value !== value && onchange(option.value)}
    >
      <!-- An icon alone (no label, its title spoken instead) is drawn at the top bar's icon size. -->
      {#if Icon}<Icon size={option.label ? 14 : 16} />{/if}{option.label}
    </button>
  {/each}
</div>

<style>
  .segmented > :global(button) {
    border-radius: 0;
  }
  .segmented > :global(button:not(:first-child)) {
    margin-left: -1px;
  }
  .segmented > :global(button:first-child) {
    border-top-left-radius: 6px;
    border-bottom-left-radius: 6px;
  }
  .segmented > :global(button:last-child) {
    border-top-right-radius: 6px;
    border-bottom-right-radius: 6px;
  }
  .segmented:focus-visible {
    outline: 2px solid var(--focus);
    outline-offset: 2px;
    border-radius: 6px;
  }
</style>
