<script lang="ts">
  let { item, size = 24 }: { item?: string; size?: number } = $props();
  /** The item whose icon wouldn't load (another item tries again). */
  let failedFor = $state<string | undefined>(undefined);
  const failed = $derived(!!item && failedFor === item);
  const src = $derived(item ? `_/icon/item/${item.replace(":", "/")}.png` : "");
</script>

{#if item && !failed}
  <img class="icon shrink-0" {src} alt="" width={size} height={size} onerror={() => (failedFor = item)} />
{:else}
  <span class="inline-block shrink-0 rounded" style="width: {size}px; height: {size}px; background: var(--panel-2)"></span>
{/if}
