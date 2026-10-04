<script lang="ts">
  import CatalogField from "../controls/CatalogField.svelte";

  /** Picks an item to show something with (a dashboard switch or display), or none. */
  let { value, disabled = false, onchange }: { value: string | undefined; disabled?: boolean; onchange: (id: string | undefined) => void } = $props();
</script>

<CatalogField
  kinds={["item"]}
  {value}
  empty="No icon"
  title="Choose an icon"
  browseDevices={false}
  {disabled}
  onpick={(p) => onchange(p.id)}
  onclear={() => onchange(undefined)}
/>
