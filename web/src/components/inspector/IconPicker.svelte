<script lang="ts">
  import CatalogField from "../controls/CatalogField.svelte";
  import RemoveButton from "../controls/RemoveButton.svelte";

  /** Picks an item to show something with (a dashboard switch or display), or none. */
  let { value, disabled = false, onchange }: { value: string | undefined; disabled?: boolean; onchange: (id: string | undefined) => void } = $props();
</script>

<div class="flex items-center gap-1">
  <div class="min-w-0 flex-1">
    <CatalogField kinds={["item"]} {value} placeholder="No icon — choose an item…" title="Choose an icon" browseDevices={false} {disabled} onpick={(p) => onchange(p.id)} />
  </div>
  {#if value && !disabled}<RemoveButton label="No icon" onclick={() => onchange(undefined)} />{/if}
</div>
