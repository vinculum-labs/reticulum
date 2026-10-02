<script lang="ts">
  import { showNode } from "../../lib/state.svelte";
  import type { SheetError } from "../../lib/types";

  /**
   * What keeps parts of a sheet from running, as the server compiled it; shown in its panel and over the canvas. An
   * error about one node shows that node when clicked.
   */
  let { sheetId, errors, overlay = false }: { sheetId: string; errors: SheetError[] | undefined; overlay?: boolean } = $props();
</script>

{#if errors?.length}
  <div class="box box-bad flex flex-col gap-0.5 text-sm" style={overlay ? "background: var(--panel)" : ""} role="alert">
    {#each errors as error}
      {#if error.node}
        <button class="error-link text-left" title="Show this node" onclick={() => showNode(sheetId, error.node!)}>{error.message}</button>
      {:else}
        <div>{error.message}</div>
      {/if}
    {/each}
  </div>
{/if}

<style>
  .error-link:hover {
    text-decoration: underline;
  }
</style>
