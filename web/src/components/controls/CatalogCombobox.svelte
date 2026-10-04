<script lang="ts">
  import ChevronDown from "@lucide/svelte/icons/chevron-down";
  import { iconUrl, matchesSearch, type CatalogEntry, type CatalogKind } from "../../lib/catalog";

  /**
   * Picks one item, fluid or chemical: the field shows the one picked (icon, name, id); typing in it lists what matches
   * under it in the same layout, and picking one closes the list. Arrow keys move, Enter picks, Esc closes.
   */
  let {
    kind,
    entries,
    value,
    placeholder = "Choose…",
    disabled = false,
    onchange,
  }: {
    kind: CatalogKind;
    entries: CatalogEntry[];
    /** The id picked, or "" for none. */
    value: string;
    placeholder?: string;
    disabled?: boolean;
    onchange: (id: string) => void;
  } = $props();

  const listId = $props.id();
  let open = $state(false);
  let text = $state("");
  let active = $state(0);
  let input: HTMLInputElement | undefined = $state();
  let list: HTMLDivElement | undefined = $state();
  const picked = $derived(entries.find((e) => e.id === value));
  /** What matches what is typed (all of them while nothing is), the first 200. */
  const matches = $derived.by(() => {
    const words = text.toLowerCase().trim().split(/\s+/).filter(Boolean);
    const out: CatalogEntry[] = [];
    for (const entry of entries) {
      if (!words.length || matchesSearch(entry, words)) {
        out.push(entry);
        if (out.length === 200) break;
      }
    }
    return out;
  });

  function show() {
    if (disabled) return;
    open = true;
    text = "";
    active = Math.max(0, matches.findIndex((e) => e.id === value));
    queueMicrotask(() => {
      input?.focus();
      list?.querySelector(`[data-index="${active}"]`)?.scrollIntoView({ block: "nearest" });
    });
  }

  function choose(entry: CatalogEntry) {
    onchange(entry.id);
    open = false;
  }

  function onkeydown(e: KeyboardEvent) {
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      active = Math.max(0, Math.min(matches.length - 1, active + (e.key === "ArrowDown" ? 1 : -1)));
      list?.querySelector(`[data-index="${active}"]`)?.scrollIntoView({ block: "nearest" });
    } else if (e.key === "Enter" && matches[active]) {
      e.preventDefault();
      choose(matches[active]);
    } else if (e.key === "Escape") {
      e.preventDefault();
      open = false;
    }
  }
</script>

<div class="relative">
  {#if open}
    <input
      bind:this={input}
      class="w-full"
      {placeholder}
      bind:value={text}
      oninput={() => (active = 0)}
      {onkeydown}
      onblur={() => setTimeout(() => (open = false), 150)}
      aria-label={placeholder}
      role="combobox"
      aria-expanded="true"
      aria-controls={listId}
    />
    <!-- Pressing on it (its scrollbar too) keeps the field focused, so the list stays open. -->
    <div bind:this={list} id={listId} class="dropdown" role="listbox" tabindex="-1" onpointerdown={(e) => e.preventDefault()}>
      {#each matches as entry, i (entry.id)}
        <button
          type="button"
          class="list-row flex w-full shrink-0 items-center gap-2 px-2 py-1 text-left"
          class:active={i === active}
          role="option"
          aria-selected={entry.id === value}
          data-index={i}
          tabindex="-1"
          onpointerdown={(e) => e.preventDefault()}
          onpointerenter={() => (active = i)}
          onclick={() => choose(entry)}
        >
          <img src={iconUrl(kind, entry.id)} alt="" width="16" height="16" class="shrink-0" style="image-rendering: pixelated" />
          <span class="min-w-0 flex-1 truncate">{entry.name}</span>
          <span class="tail min-w-0 max-w-[45%] truncate text-xs muted" title={entry.id}>&lrm;{entry.id}&lrm;</span>
        </button>
      {:else}
        <p class="px-2 py-1 muted">Nothing matches.</p>
      {/each}
    </div>
  {:else}
    <button type="button" class="select flex w-full items-center gap-2 text-left" {disabled} onclick={show} aria-haspopup="listbox">
      {#if picked}
        <img src={iconUrl(kind, picked.id)} alt="" width="16" height="16" class="shrink-0" style="image-rendering: pixelated" />
        <span class="min-w-0 flex-1 truncate">{picked.name}</span>
        <span class="tail min-w-0 max-w-[45%] truncate text-xs muted" title={picked.id}>&lrm;{picked.id}&lrm;</span>
      {:else}
        <span class="min-w-0 flex-1 truncate muted">{placeholder}</span>
      {/if}
      <ChevronDown size={14} class="shrink-0 muted" />
    </button>
  {/if}
</div>

<style>
  /* Framed like a dropdown (Select's look). */
  .select {
    background: var(--field);
    border: 1px solid var(--line);
    border-radius: 6px;
    padding: 3px 7px;
  }
  /* An id cut from its start ("…:polished_andesite_stairs"): what it is matters more than its mod. Laid out right to
     left so the ellipsis goes first; the marks round it keep its ":" in place. */
  .tail {
    direction: rtl;
    text-align: left;
  }
  .select:disabled {
    opacity: 0.5;
  }
  .dropdown {
    position: absolute;
    z-index: 30;
    left: 0;
    right: 0;
    top: calc(100% + 2px);
    max-height: 260px;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    padding: 4px 0;
    border: 1px solid var(--line);
    border-radius: 6px;
    background: var(--panel);
    box-shadow: 0 6px 18px rgb(0 0 0 / 0.35);
  }
</style>
