<script lang="ts">
  import Funnel from "@lucide/svelte/icons/funnel";
  import type { CatalogKind } from "../../lib/catalog";
  import type { GlobalPos } from "../../lib/types";
  import FilterInput from "./FilterInput.svelte";
  import FilterPicker from "./FilterPicker.svelte";

  /** A filter box with a funnel beside it (the Filter node's icon), which opens the picker on the right list. */
  let {
    value,
    onchange,
    disabled = false,
    placeholder,
    kind,
    anyResource = false,
    devices = [],
  }: {
    value: string;
    onchange: (value: string) => void;
    disabled?: boolean;
    placeholder?: string;
    /** What the filter is for, so it's checked as that and the picker opens on the right list. */
    kind?: CatalogKind;
    /** Checked as fine when it works for any resource (a filter template). */
    anyResource?: boolean;
    /** Devices whose contents the picker offers first (e.g. those feeding this node). */
    devices?: GlobalPos[];
  } = $props();

  let picking = $state(false);
  let text = $state("");
</script>

<FilterInput bind:text {value} kind={kind ?? "item"} {anyResource} {disabled} {placeholder} oncommit={onchange}>
  {#snippet extra()}
    <button type="button" class="btn flex shrink-0 items-center px-2" {disabled} title="Pick items, fluids or chemicals, their tags or mods" aria-label="Pick" onclick={() => (picking = true)}><Funnel size={14} /></button>
  {/snippet}
</FilterInput>

{#if picking}
  <FilterPicker
    value={text}
    kind={kind ?? "item"}
    {devices}
    onclose={() => (picking = false)}
    onapply={(v) => {
      text = v;
      if (v !== value) onchange(v);
    }}
  />
{/if}
