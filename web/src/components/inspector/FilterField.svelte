<script lang="ts">
  import type { CatalogKind } from "../../lib/catalog";
  import { request } from "../../lib/state.svelte";
  import type { GlobalPos } from "../../lib/types";
  import FilterPicker from "./FilterPicker.svelte";

  let {
    value,
    onchange,
    disabled = false,
    placeholder = "anything (e.g. tag:c:ores & !mod:create)",
    kind,
    devices = [],
  }: {
    value: string;
    onchange: (value: string) => void;
    disabled?: boolean;
    placeholder?: string;
    /** What the filter is for, so the picker opens on the right list. */
    kind?: CatalogKind;
    /** Devices whose contents the picker offers first (e.g. those feeding this node). */
    devices?: GlobalPos[];
  } = $props();

  let picking = $state(false);

  let text = $state("");
  let error = $state<string | null>(null);
  let timer: ReturnType<typeof setTimeout> | undefined;

  $effect(() => {
    text = value;
  });

  function check() {
    clearTimeout(timer);
    const expression = text;
    timer = setTimeout(async () => {
      try {
        const r = await request("checkFilter", { expression, resource: kind ?? "item" });
        if (expression === text) error = r.valid ? null : r.message;
      } catch {
        error = null;
      }
    }, 250);
  }

  function commit() {
    if (text !== value) onchange(text);
  }
</script>

<div class="flex flex-col gap-0.5">
  <div class="flex gap-1">
    <input
      class="min-w-0 flex-1 font-mono text-sm"
      style={error ? "border-color: var(--bad)" : ""}
      bind:value={text}
      {placeholder}
      {disabled}
      spellcheck="false"
      oninput={check}
      onblur={commit}
      onkeydown={(e) => e.key === "Enter" && (commit(), e.currentTarget.blur())}
    />
    <button class="btn shrink-0 text-sm" {disabled} title="Pick items, fluids or chemicals, their tags or mods" onclick={() => (picking = true)}>Pick…</button>
  </div>
  {#if error}<span class="text-xs" style="color: var(--bad)">{error}</span>{/if}
</div>

{#if picking}
  <FilterPicker
    value={text}
    kind={kind ?? "item"}
    {devices}
    onclose={() => (picking = false)}
    onapply={(v) => {
      text = v;
      check();
      if (v !== value) onchange(v);
    }}
  />
{/if}
