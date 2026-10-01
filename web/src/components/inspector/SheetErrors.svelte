<script lang="ts">
  /** What keeps parts of a sheet from running, as the server compiled it; shown in its panel and over the canvas. */
  let { errors, overlay = false }: { errors: string[] | undefined; overlay?: boolean } = $props();
</script>

{#if errors?.length}
  <div class="box box-bad flex flex-col gap-0.5 text-sm" style={overlay ? "background: var(--panel)" : ""} role="alert">
    {#each errors as error}<div>{error}</div>{/each}
  </div>
{/if}
