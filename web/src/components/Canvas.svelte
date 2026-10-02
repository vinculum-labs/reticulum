<script lang="ts">
  import { app } from "../lib/state.svelte";
  import Flow from "./flow/Flow.svelte";
  import EmptyState from "./EmptyState.svelte";
</script>

{#if app.sheetId}
  <!-- One canvas for every sheet: re-making Svelte Flow per sheet leaves its handles on a dead store. -->
  <Flow />
{:else}
  <EmptyState fill message="Add a sheet on the left to start building." />
{/if}
