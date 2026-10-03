<script lang="ts">
  import { app, showNode } from "../../lib/state.svelte";
  import type { GraphNode, SheetError } from "../../lib/types";
  import NodeChip from "../NodeChip.svelte";

  /**
   * What keeps parts of a sheet from running, as the server compiled it; shown in its panel and over the canvas. An
   * error about one node shows that node when clicked, and shows the node itself (icon, name, what it is) in place of
   * its name in front of the message.
   */
  let { sheetId, errors, overlay = false }: { sheetId: string; errors: SheetError[] | undefined; overlay?: boolean } = $props();

  function nodeOf(id: string): GraphNode | undefined {
    for (const sheet of app.snapshot?.sheets ?? []) {
      const found = sheet.nodes?.find((n) => n.id === id);
      if (found) return found;
    }
    return undefined;
  }
</script>

{#if errors?.length}
  <!-- The rows carry the box's padding, so a lit row reaches its border (the rounded corners clip it). -->
  <div class="errors box box-bad flex flex-col overflow-hidden text-sm" style={overlay ? "background: var(--panel)" : ""} role="alert">
    {#each errors as error}
      {@const node = error.node && error.detail ? nodeOf(error.node) : undefined}
      {#if error.node}
        <button class="error-link row flex flex-wrap items-center gap-x-2 gap-y-1 text-left" title="Show this node" onclick={() => showNode(sheetId, error.node!)}>
          {#if node}
            <NodeChip {node} />
            <!-- Its own sentence now that the name is the chip: capitalized. -->
            <span class="message min-w-0 flex-1">{error.detail!.charAt(0).toUpperCase() + error.detail!.slice(1)}</span>
          {:else}
            <span class="message">{error.message}</span>
          {/if}
        </button>
      {:else}
        <div class="row">{error.message}</div>
      {/if}
    {/each}
  </div>
{/if}

<style>
  .errors {
    padding: 0;
  }
  .row {
    padding: 0.375rem 0.5rem;
  }
  /* A clickable row lights up as a whole, in the box's red, rather than underlining its text. */
  .error-link {
    cursor: pointer;
  }
  .error-link:hover,
  .error-link:focus-visible {
    background: color-mix(in srgb, var(--bad) 14%, transparent);
  }
</style>
