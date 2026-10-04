<script lang="ts">
  import ColorField from "./ColorField.svelte";
  import Popover from "./Popover.svelte";

  /** A color as one swatch (struck through for the default); clicking it opens the color choices. */
  let { value, label = "Color", disabled = false, onchange }: { value: string | undefined; label?: string; disabled?: boolean; onchange: (color: string | undefined) => void } = $props();

  let open = $state(false);
  let button: HTMLButtonElement | undefined = $state();
</script>

<button
  bind:this={button}
  type="button"
  class="swatch"
  class:none={!value}
  style={value ? `background: ${value}` : ""}
  {disabled}
  title={label}
  aria-label={label}
  aria-haspopup="dialog"
  onclick={() => (open = !open)}
></button>
<Popover bind:open anchor={button} width={232} {label}>
  <div class="p-2">
    <ColorField
      {value}
      onchange={(c) => {
        onchange(c);
        open = false;
      }}
    />
  </div>
</Popover>

<style>
  .swatch {
    width: 20px;
    height: 20px;
    flex-shrink: 0;
    border-radius: 4px;
    border: 1px solid var(--line);
    filter: drop-shadow(0 0 1px rgb(0 0 0 / 0.6));
  }
  .swatch:disabled {
    opacity: 0.5;
  }
  /* The default: white, struck through from the bottom left corner to the top right (as in ColorField). */
  .swatch.none {
    background: linear-gradient(to bottom right, #fff calc(50% - 1px), #e0302a calc(50% - 1px), #e0302a calc(50% + 1px), #fff calc(50% + 1px));
  }
</style>
