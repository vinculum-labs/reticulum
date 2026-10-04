<script lang="ts">
  import ChevronRight from "@lucide/svelte/icons/chevron-right";
  import { nodeTitle, openCopy, openSheet } from "../../lib/state.svelte";
  import { ancestors } from "../../lib/tree";
  import type { GraphNode, Sheet, Snapshot } from "../../lib/types";

  /**
   * Where the open sheet sits: the sheets above it, from the top, each opening on a click. Blueprints stand alone;
   * an open copy sits where its node is, after the copies it was opened through (a copy inside a copy's blueprint).
   */
  let {
    sheet,
    snapshot,
    copy = null,
  }: { sheet: Sheet; snapshot: Snapshot; copy?: { sheet: Sheet; node: GraphNode; outer: { sheet: string; node: string }[] } | null } = $props();

  /** The copies opened through, each with what it is on and the way into it. */
  const through = $derived(
    (copy?.outer ?? []).map((step, i) => {
      const on = snapshot.sheets.find((s) => s.id === step.sheet);
      return { step, node: on?.nodes?.find((n) => n.id === step.node), before: copy!.outer.slice(0, i) };
    }),
  );
  /** The sheet the way in starts on: the first copy's, or the open copy's own. */
  const start = $derived(copy ? (snapshot.sheets.find((s) => s.id === copy.outer[0]?.sheet) ?? copy.sheet) : null);
  const trail = $derived(start ? [...ancestors(snapshot, start), start] : sheet.blueprint ? [] : ancestors(snapshot, sheet));
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
  {#each through as { step, node, before } (step.node)}
    <button class="list-row min-w-0 truncate rounded px-1" onclick={() => openCopy(step.sheet, step.node, before)} title="Open this copy">
      {node ? nodeTitle(node) : "?"}
    </button>
    <ChevronRight size={12} class="shrink-0 muted" />
  {/each}
  {#if copy}
    <span class="min-w-0 truncate px-1 font-medium">{nodeTitle(copy.node)}</span>
    <span class="shrink-0 muted">(copy)</span>
  {:else}
    <span class="min-w-0 truncate px-1 font-medium">{sheet.name}</span>
  {/if}
</nav>
