<script lang="ts">
  import ChevronRight from "@lucide/svelte/icons/chevron-right";
  import { nodeTitle, openInstance, openSheet } from "../../lib/state.svelte";
  import { ancestors } from "../../lib/tree";
  import type { GraphNode, Sheet, Snapshot } from "../../lib/types";

  /**
   * Where the open sheet sits: the sheets above it, from the top, each opening on a click. Blueprints stand alone;
   * an open instance sits where its node is, after the instances it was opened through (an instance inside an instance's blueprint).
   */
  let {
    sheet,
    snapshot,
    instance = null,
  }: { sheet: Sheet; snapshot: Snapshot; instance?: { sheet: Sheet; node: GraphNode; outer: { sheet: string; node: string }[] } | null } = $props();

  /** The instances opened through, each with what it is on and the way into it. */
  const through = $derived(
    (instance?.outer ?? []).map((step, i) => {
      const on = snapshot.sheets.find((s) => s.id === step.sheet);
      return { step, node: on?.nodes?.find((n) => n.id === step.node), before: instance!.outer.slice(0, i) };
    }),
  );
  /** The sheet the way in starts on: the first instance's, or the open instance's own. */
  const start = $derived(instance ? (snapshot.sheets.find((s) => s.id === instance.outer[0]?.sheet) ?? instance.sheet) : null);
  const trail = $derived(start ? [...ancestors(snapshot, start), start] : sheet.blueprint ? [] : ancestors(snapshot, sheet));
</script>

<nav class="flex max-w-xl items-center gap-0.5 rounded-lg border border-line bg-panel px-2 py-1 text-sm" aria-label="Where this sheet is">
  {#if sheet.blueprint && !instance}
    <span class="muted">Blueprint</span>
    <ChevronRight size={12} class="shrink-0 muted" />
  {/if}
  {#each trail as above (above.id)}
    <button class="list-row min-w-0 truncate rounded px-1" onclick={() => openSheet(above.id)} title={`Open ${above.name}`}>{above.name}</button>
    <ChevronRight size={12} class="shrink-0 muted" />
  {/each}
  {#each through as { step, node, before } (step.node)}
    <button class="list-row min-w-0 truncate rounded px-1" onclick={() => openInstance(step.sheet, step.node, before)} title="Open this placed blueprint">
      {node ? nodeTitle(node) : "?"}
    </button>
    <ChevronRight size={12} class="shrink-0 muted" />
  {/each}
  {#if instance}
    <span class="min-w-0 truncate px-1 font-medium">{nodeTitle(instance.node)}</span>
    <span class="shrink-0 muted">(placed blueprint)</span>
  {:else}
    <span class="min-w-0 truncate px-1 font-medium">{sheet.name}</span>
  {/if}
</nav>
