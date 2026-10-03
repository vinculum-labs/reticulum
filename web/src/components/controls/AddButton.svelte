<script lang="ts">
  import Plus from "@lucide/svelte/icons/plus";

  /**
   * The + beside a list's heading that adds one more to it (the counterpart of RemoveButton's ✕). {@code label} says
   * what it adds: the tooltip and the spoken name.
   */
  let { label, onclick, disabled = false }: { label: string; onclick: () => void; disabled?: boolean } = $props();
</script>

<button type="button" class="btn add-button flex shrink-0 items-center justify-center" title={label} aria-label={label} {disabled} {onclick}>
  <Plus size={16} />
</button>

<style>
  .add-button {
    width: 28px;
    height: 26px;
    padding: 0;
  }
</style>
