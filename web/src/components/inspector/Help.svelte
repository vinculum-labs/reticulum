<script lang="ts">
  import type { Snippet } from "svelte";
  import { help } from "../../lib/help.svelte";

  /** A note on how something works: shown only while the panel's help is on (the ? in its header). */
  let { children }: { children: Snippet } = $props();
</script>

{#if help.shown}<p class="text-xs muted">{@render children()}</p>{/if}
