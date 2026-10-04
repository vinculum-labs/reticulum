<script lang="ts">
  import type { Snippet } from "svelte";
  import ArrowLeft from "@lucide/svelte/icons/arrow-left";
  import CircleHelp from "@lucide/svelte/icons/circle-help";
  import { help, toggleHelp } from "../../lib/help.svelte";

  /**
   * The frame of everything the side panel shows: a title (with an optional icon, subtitle and actions), then the
   * content. {@code onclose} adds a Close button (Esc leaves it open, as for every tab), {@code onback} a Back button above. The ? shows
   * or hides every panel's explanations.
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

</script>

<div class="flex flex-col gap-3">
  {#if onback}
    <button class="btn flex items-center gap-1 self-start text-sm" onclick={onback}><ArrowLeft size={14} />{backLabel}</button>
  {/if}
  <!-- The icon, title and buttons on one line; a long title wraps rather than being cut off. -->
  <div class="flex flex-col gap-1">
    <div class="flex items-center gap-2">
      {#if icon}{@render icon()}{/if}
      <h2 class="min-w-0 flex-1 text-base font-semibold break-words">{title}</h2>
      <button
        type="button"
        class="btn shrink-0 px-2"
        aria-pressed={help.shown}
        title={help.shown ? "Hide explanations" : "Show explanations"}
        aria-label="Explanations"
        onclick={toggleHelp}><CircleHelp size={16} /></button
      >
      {#if actions}{@render actions()}{/if}
      {#if onclose}<button class="btn shrink-0 text-sm" onclick={onclose}>Close</button>{/if}
    </div>
    <!-- What it is, under the whole line and flush with the rest of the panel's text, wrapped in full. -->
    {#if subtitle}<p class="text-xs muted">{subtitle}</p>{/if}
  </div>
  {@render children()}
</div>
