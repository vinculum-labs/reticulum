<script lang="ts">
  import { SvelteFlowProvider } from "@xyflow/svelte";
  import { app } from "../lib/state.svelte";
  import Flow from "./flow/Flow.svelte";
</script>

{#if app.sheetId}
  <SvelteFlowProvider>
    {#key app.sheetId}
      <Flow />
    {/key}
  </SvelteFlowProvider>
{:else}
  <div class="grid h-full place-items-center muted">Add a sheet on the left to start building.</div>
{/if}
