<script lang="ts">
  import LoaderCircle from "@lucide/svelte/icons/loader-circle";

  /** A message where there is nothing to show yet: filling the area (the canvas, the page) or as a line in a panel. */
  let { message, fill = false, loading = false }: { message: string; fill?: boolean; loading?: boolean } = $props();
</script>

<div class="{fill ? 'grid h-full flex-1 place-items-center' : ''} muted" role="status" aria-busy={loading}>
  <span class="flex items-center gap-2 text-sm">
    {#if loading}<LoaderCircle size={16} class="spin" />{/if}{message}
  </span>
</div>

<style>
  :global(.spin) {
    animation: spin 1s linear infinite;
  }
  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
</style>
