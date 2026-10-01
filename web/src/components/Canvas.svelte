<script lang="ts">
  import { app } from "../lib/state.svelte";
  import Flow from "./flow/Flow.svelte";
  import EmptyState from "./EmptyState.svelte";
</script>

{#if app.sheetId}
  {#key app.sheetId}
    <Flow />
  {/key}
{:else}
  <EmptyState fill message="Add a sheet on the left to start building." />
{/if}
