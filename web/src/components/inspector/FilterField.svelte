<script lang="ts">
  import { request } from "../../lib/state.svelte";

  let {
    value,
    onchange,
    disabled = false,
    placeholder = "anything (e.g. tag:c:ores & !mod:create)",
  }: { value: string; onchange: (value: string) => void; disabled?: boolean; placeholder?: string } = $props();

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
        const r = await request("checkFilter", { expression });
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
  <input
    class="w-full font-mono text-sm"
    style={error ? "border-color: var(--bad)" : ""}
    bind:value={text}
    {placeholder}
    {disabled}
    spellcheck="false"
    oninput={check}
    onblur={commit}
    onkeydown={(e) => e.key === "Enter" && (commit(), e.currentTarget.blur())}
  />
  {#if error}<span class="text-xs" style="color: var(--bad)">{error}</span>{/if}
</div>
