<script lang="ts">
  import Check from "@lucide/svelte/icons/check";

  /** Minecraft's 16 dye colors, as the game draws them. */
  const DYES: { name: string; hex: string }[] = [
    { name: "White", hex: "#f9fffe" },
    { name: "Light gray", hex: "#9d9d97" },
    { name: "Gray", hex: "#474f52" },
    { name: "Black", hex: "#1d1d21" },
    { name: "Brown", hex: "#835432" },
    { name: "Red", hex: "#b02e26" },
    { name: "Orange", hex: "#f9801d" },
    { name: "Yellow", hex: "#fed83d" },
    { name: "Lime", hex: "#80c71f" },
    { name: "Green", hex: "#5e7c16" },
    { name: "Cyan", hex: "#169c9c" },
    { name: "Light blue", hex: "#3ab3da" },
    { name: "Blue", hex: "#3c44aa" },
    { name: "Purple", hex: "#8932b8" },
    { name: "Magenta", hex: "#c74ebd" },
    { name: "Pink", hex: "#f38baa" },
  ];

  /** A color: the default, one of the dye colors, or any other ("#rrggbb"). */
  let { value, disabled = false, onchange }: { value: string | undefined; disabled?: boolean; onchange: (color: string | undefined) => void } = $props();

  const custom = $derived(!!value && !DYES.some((d) => d.hex === value.toLowerCase()));
</script>

<div class="flex flex-wrap items-center gap-1" role="radiogroup" aria-label="Color">
  <button type="button" class="btn px-2 text-xs" role="radio" aria-checked={!value} {disabled} onclick={() => onchange(undefined)}>Default</button>
  {#each DYES as dye (dye.hex)}
    {@const on = value?.toLowerCase() === dye.hex}
    <button type="button" class="swatch" role="radio" aria-checked={on} aria-label={dye.name} title={dye.name} {disabled} style="background: {dye.hex}" onclick={() => onchange(dye.hex)}>
      {#if on}<Check size={12} strokeWidth={3} />{/if}
    </button>
  {/each}
  <label class="swatch custom" class:on={custom} title="Another color" style={custom ? `background: ${value}` : ""}>
    <input type="color" class="sr-only" value={custom ? value : "#ffffff"} {disabled} onchange={(e) => onchange(e.currentTarget.value)} aria-label="Another color" />
    {#if custom}<Check size={12} strokeWidth={3} />{:else}+{/if}
  </label>
</div>

<style>
  .swatch {
    width: 20px;
    height: 20px;
    border-radius: 4px;
    border: 1px solid var(--line);
    display: inline-flex;
    align-items: center;
    justify-content: center;
    color: #fff;
    cursor: pointer;
    filter: drop-shadow(0 0 1px rgb(0 0 0 / 0.6));
  }
  .swatch:disabled {
    opacity: 0.5;
    cursor: default;
  }
  .swatch.custom {
    background: conic-gradient(red, yellow, lime, cyan, blue, magenta, red);
    font-size: 13px;
    line-height: 1;
  }
</style>
