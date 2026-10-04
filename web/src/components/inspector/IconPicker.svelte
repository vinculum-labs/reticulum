<script lang="ts">
  import { loadCatalog, type Catalog } from "../../lib/catalog";
  import CatalogCombobox from "../controls/CatalogCombobox.svelte";
  import RemoveButton from "../controls/RemoveButton.svelte";

  /** Picks an item to show something with (a dashboard switch or display), or none. */
  let { value, disabled = false, onchange }: { value: string | undefined; disabled?: boolean; onchange: (id: string | undefined) => void } = $props();

  let catalog = $state<Catalog | null>(null);
  loadCatalog().then((c) => (catalog = c)).catch(() => {});
</script>

<div class="flex items-center gap-1">
  <div class="min-w-0 flex-1">
    <CatalogCombobox kind="item" entries={catalog?.item ?? []} value={value ?? ""} placeholder="No icon — choose an item…" {disabled} onchange={(id) => onchange(id)} />
  </div>
  {#if value && !disabled}<RemoveButton label="No icon" onclick={() => onchange(undefined)} />{/if}
</div>
