<script lang="ts">
  import X from "@lucide/svelte/icons/x";

  /** The small ✕ that removes one thing from a list. {@code label} says what (it's the tooltip and the spoken name). */
  let {
    label,
    onclick,
    disabled = false,
    reveal = false,
    class: className = "",
  }: {
    label: string;
    onclick: (e: MouseEvent) => void;
    disabled?: boolean;
    /** Shown only while its row (a `group`) is hovered or it has focus. */
    reveal?: boolean;
    class?: string;
  } = $props();
</script>

<button
  type="button"
  class="remove-button flex shrink-0 items-center justify-center rounded muted {reveal ? 'reveal' : ''} {className}"
  title={label}
  aria-label={label}
  {disabled}
  {onclick}
>
  <X size={14} />
</button>

<style>
  .remove-button {
    width: 20px;
    height: 20px;
  }
  .remove-button:hover:not(:disabled) {
    color: var(--bad);
    background: var(--panel-2);
  }
  .remove-button:disabled {
    opacity: 0.4;
    cursor: default;
  }
</style>
