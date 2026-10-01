<script lang="ts">
  /**
   * An on/off switch for things that run (the network, a sheet, a folder, a node). Checkboxes stay for options and
   * picking several faces.
   */
  let {
    checked,
    onchange,
    label,
    hideLabel = false,
    disabled = false,
    title,
  }: {
    checked: boolean;
    onchange: (on: boolean) => void;
    label: string;
    /** The label is only spoken (and the tooltip), not shown: for compact rows. */
    hideLabel?: boolean;
    disabled?: boolean;
    title?: string;
  } = $props();
</script>

<button
  type="button"
  role="switch"
  aria-checked={checked}
  aria-label={hideLabel ? label : undefined}
  title={title ?? (hideLabel ? label : undefined)}
  class="switch flex shrink-0 items-center gap-1.5 text-sm"
  {disabled}
  onclick={() => onchange(!checked)}
>
  <span class="track" class:on={checked}><span class="thumb"></span></span>
  {#if !hideLabel}{label}{/if}
</button>

<style>
  .switch:disabled {
    opacity: 0.5;
    cursor: default;
  }
  .track {
    position: relative;
    width: 28px;
    height: 16px;
    border-radius: 999px;
    background: var(--line);
    transition: background 0.15s;
  }
  .track.on {
    background: var(--accent);
  }
  .thumb {
    position: absolute;
    top: 2px;
    left: 2px;
    width: 12px;
    height: 12px;
    border-radius: 50%;
    background: var(--panel);
    transition: left 0.15s;
  }
  .track.on .thumb {
    left: 14px;
  }
  .switch:focus-visible .track {
    outline: 2px solid var(--accent);
    outline-offset: 2px;
  }
</style>
