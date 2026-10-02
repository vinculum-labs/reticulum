<script lang="ts">
  import { app } from "../lib/state.svelte";
  import Flow from "./flow/Flow.svelte";
  import EmptyState from "./EmptyState.svelte";
</script>

{#if app.sheetId}
  <!-- A canvas per sheet (and per open copy): each starts at its own remembered view. -->
  {#key `${app.sheetId}/${app.copy?.node ?? ""}`}
    <Flow />
  {/key}
{:else}
  <EmptyState fill message="Add a sheet on the left to start building." />
{/if}
