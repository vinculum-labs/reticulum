<script lang="ts">
  import type { Component } from "svelte";
  import Plus from "@lucide/svelte/icons/plus";

  /**
   * The + beside a list's heading that adds one more to it: the counterpart of RemoveButton's ✕, the same size and
   * look. {@code label} says what it adds: the tooltip and the spoken name. {@code inset} sets it in by a box's border
   * and padding, to line up with the ✕ of the boxed groups below it. {@code icon} draws another heading action in the
   * same look (Import beside the Sheets +).
   */
  let {
    label,
    onclick,
    disabled = false,
    inset = false,
    icon: Icon = Plus,
  }: { label: string; onclick: () => void; disabled?: boolean; inset?: boolean; icon?: Component<{ size?: number | string }> } = $props();
</script>

<button type="button" class="add-button flex shrink-0 items-center justify-center rounded muted" class:inset title={label} aria-label={label} {disabled} onclick={() => onclick()}>
  <Icon size={16} />
</button>

<style>
  .add-button {
    width: 20px;
    height: 20px;
  }
  .add-button.inset {
    margin-right: 9px;
  }
  /* Lights up in the accent on hover, as the ✕ does in red: adding, not removing. */
  .add-button:hover:not(:disabled),
  .add-button:focus-visible {
    color: var(--accent);
    background: color-mix(in srgb, var(--accent) 22%, transparent);
  }
  .add-button:disabled {
    opacity: 0.4;
  }
</style>
