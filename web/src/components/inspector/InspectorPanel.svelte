<script lang="ts">
  import type { Snippet } from "svelte";
  import ArrowLeft from "@lucide/svelte/icons/arrow-left";
  import { onEscape } from "../../lib/escape";

  /**
   * The frame of everything the side panel shows: a title (with an optional icon, subtitle and actions), then the
   * content. {@code onclose} adds a Close button and lets Esc close it; {@code onback} adds a Back button above.
   */
  let {
    title,
    subtitle,
    icon,
    actions,
    onclose,
    onback,
    backLabel = "Back",
    children,
  }: {
    title: string;
    subtitle?: string;
    icon?: Snippet;
    actions?: Snippet;
    onclose?: () => void;
    onback?: () => void;
    backLabel?: string;
    children: Snippet;
  } = $props();

  $effect(() => {
    if (onclose) return onEscape(onclose);
  });
</script>

<div class="flex flex-col gap-3">
  {#if onback}
    <button class="btn flex items-center gap-1 self-start text-sm" onclick={onback}><ArrowLeft size={14} />{backLabel}</button>
  {/if}
  <div class="flex items-center gap-2">
    {#if icon}{@render icon()}{/if}
    <div class="min-w-0 flex-1">
      <h2 class="truncate text-base font-semibold" title={title}>{title}</h2>
      {#if subtitle}<p class="truncate text-xs muted" title={subtitle}>{subtitle}</p>{/if}
    </div>
    {#if actions}{@render actions()}{/if}
    {#if onclose}<button class="btn shrink-0 text-sm" onclick={onclose}>Close</button>{/if}
  </div>
  {@render children()}
</div>
