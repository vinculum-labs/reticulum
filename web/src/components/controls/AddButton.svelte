<script lang="ts">
  import Plus from "@lucide/svelte/icons/plus";

  /**
   * The + beside a list's heading that adds one more to it: the counterpart of RemoveButton's ✕, the same size and
   * look, and set in so it lines up with the ✕ of the groups below (a box's border and padding). {@code label} says
   * what it adds: the tooltip and the spoken name. {@code flush} drops the inset, for rows not in boxes (their ✕ at
   * the very edge).
   */
  let { label, onclick, disabled = false, flush = false }: { label: string; onclick: () => void; disabled?: boolean; flush?: boolean } = $props();
</script>

<button type="button" class="add-button flex shrink-0 items-center justify-center rounded muted" class:flush title={label} aria-label={label} {disabled} {onclick}>
  <Plus size={16} />
</button>

<style>
  .add-button {
    width: 20px;
    height: 20px;
    margin-right: 9px;
  }
  /* Lights up in the accent on hover, as the ✕ does in red: adding, not removing. */
  .add-button:hover:not(:disabled),
  .add-button:focus-visible {
    color: var(--accent);
    background: color-mix(in srgb, var(--accent) 22%, transparent);
  }
  .add-button.flush {
    margin-right: 0;
  }
  .add-button:disabled {
    opacity: 0.4;
  }
</style>
