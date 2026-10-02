<script lang="ts">
  import ChevronRight from "@lucide/svelte/icons/chevron-right";
  import { nodeTitle, openSheet } from "../../lib/state.svelte";
  import { ancestors } from "../../lib/tree";
  import type { Sheet, Snapshot } from "../../lib/types";

  /**
   * Where the open sheet sits: the sheets above it, from the top, each opening on a click. Blueprints stand alone;
   * an open copy sits where its node is.
   */
  let { sheet, snapshot, copy = null }: { sheet: Sheet; snapshot: Snapshot; copy?: { sheet: Sheet; node: import("../../lib/types").GraphNode } | null } = $props();

  const trail = $derived(copy ? [...ancestors(snapshot, copy.sheet), copy.sheet] : sheet.blueprint ? [] : ancestors(snapshot, sheet));
</script>

<nav class="flex max-w-xl items-center gap-0.5 rounded-lg border border-line bg-panel px-2 py-1 text-sm" aria-label="Where this sheet is">
  {#if sheet.blueprint && !copy}
    <span class="muted">Blueprint</span>
    <ChevronRight size={12} class="shrink-0 muted" />
  {/if}
  {#each trail as above (above.id)}
    <button class="list-row min-w-0 truncate rounded px-1" onclick={() => openSheet(above.id)} title={`Open ${above.name}`}>{above.name}</button>
    <ChevronRight size={12} class="shrink-0 muted" />
  {/each}
  {#if copy}
    <span class="min-w-0 truncate px-1 font-medium">{nodeTitle(copy.node)}</span>
    <span class="shrink-0 muted">(copy)</span>
  {:else}
    <span class="min-w-0 truncate px-1 font-medium">{sheet.name}</span>
  {/if}
</nav>
