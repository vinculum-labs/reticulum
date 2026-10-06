<script lang="ts">
  import type { Snippet } from "svelte";
  import CircleHelp from "@lucide/svelte/icons/circle-help";
  import type { CatalogKind } from "../../lib/catalog";
  import { request, resync, app } from "../../lib/state.svelte";
  import FilterSyntax from "./FilterSyntax.svelte";

  /**
   * A filter's text box: checked by the server as you type (and when shown, so a saved filter that no longer works
   * is flagged), with ? showing the syntax below. With {@code oncommit} it saves like a TextField (Enter or leaving
   * it, Esc cancels); without, {@code text} is simply bound (the picker applies it itself).
   */
  let {
    text = $bindable(""),
    value,
    kind = "item",
    anyResource = false,
    template,
    oncommit,
    disabled = false,
    placeholder = "anything (e.g. tag:c:ores & !mod:create)",
    error = $bindable(null),
    label = "Filter",
    extra,
  }: {
    text?: string;
    /** The saved filter, followed when it changes (unless being typed in). */
    value?: string;
    kind?: CatalogKind;
    /** Checked as fine when it works for any resource (a template: used by item, fluid and chemical filters alike). */
    anyResource?: boolean;
    /** The template this is the filter of: checked as saving it would be (its new filter, with the others). */
    template?: string;
    oncommit?: (value: string) => void;
    disabled?: boolean;
    placeholder?: string;
    error?: string | null;
    label?: string;
    /** Buttons between the box and ? (the field's picker funnel). */
    extra?: Snippet;
  } = $props();

  let helping = $state(false);
  let focused = false;
  let element: HTMLInputElement | undefined = $state();

  // Follows the saved filter, and goes back to it when a save is refused (the saved one didn't change).
  $effect(() => {
    void resync.count;
    const saved = value;
    if (saved !== undefined && !focused) text = saved;
  });

  /** The templates as text: changes only when they do (not with every snapshot). */
  const templatesKey = $derived(JSON.stringify(app.snapshot?.filters ?? {}));

  // Checked a moment after the text stops changing, and once when shown.
  let timer: ReturnType<typeof setTimeout> | undefined;
  $effect(() => {
    const expression = text;
    const resource = anyResource ? "any" : kind;
    // Checked again when the templates change (one it names removed or renamed).
    void templatesKey;
    clearTimeout(timer);
    timer = setTimeout(async () => {
      try {
        const r = await request("checkFilter", { expression, resource, ...(template ? { template } : {}) });
        if (expression === text) error = r.valid ? null : r.message;
      } catch {
        if (expression === text) error = null;
      }
    }, 250);
    return () => clearTimeout(timer);
  });

  function commit() {
    if (oncommit && value !== undefined && text !== value) oncommit(text);
  }

  function onkeydown(e: KeyboardEvent) {
    if (!oncommit) return;
    if (e.key === "Enter") {
      e.preventDefault();
      element?.blur();
    } else if (e.key === "Escape") {
      e.preventDefault();
      text = value ?? "";
      element?.blur();
    }
  }
</script>

<div class="flex flex-col gap-0.5">
  <div class="flex gap-1">
    <input
      bind:this={element}
      bind:value={text}
      aria-label={label}
      aria-invalid={!!error}
      class="min-w-0 flex-1 font-mono text-sm"
      style={error ? "border-color: var(--bad)" : ""}
      {placeholder}
      {disabled}
      spellcheck="false"
      onfocus={() => (focused = true)}
      onblur={() => {
        focused = false;
        commit();
      }}
      {onkeydown}
    />
    {#if extra}{@render extra()}{/if}
    <button
      type="button"
      class="btn flex shrink-0 items-center px-2"
      aria-pressed={helping}
      title="Filter syntax"
      aria-label="Filter syntax"
      aria-expanded={helping}
      onclick={() => (helping = !helping)}><CircleHelp size={14} /></button
    >
  </div>
  {#if error}<span class="text-xs text-bad" role="alert">{error}</span>{/if}
  {#if helping}<FilterSyntax />{/if}
</div>
