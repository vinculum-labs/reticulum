<script lang="ts">
  let { item, size = 24 }: { item?: string; size?: number } = $props();
  let failed = $state(false);
  const src = $derived(item ? `/icon/item/${item.replace(":", "/")}.png` : "");
</script>

{#if item && !failed}
  <img class="icon shrink-0" {src} alt="" width={size} height={size} onerror={() => (failed = true)} />
{:else}
  <span class="inline-block shrink-0 rounded" style="width: {size}px; height: {size}px; background: var(--panel-2)"></span>
{/if}
