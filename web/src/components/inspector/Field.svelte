<script lang="ts">
  import type { Snippet } from "svelte";

  let { label, hint, children }: { label: string; hint?: string; children: Snippet } = $props();
</script>

<label class="flex flex-col gap-1">
  <span class="text-xs font-medium muted">{label}</span>
  {@render children()}
  {#if hint}<span class="text-xs muted">{hint}</span>{/if}
</label>
