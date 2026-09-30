<script lang="ts">
  import { untrack } from "svelte";
  import {
    CATALOG_KIND_LABEL,
    iconUrl,
    itemTerm,
    loadCatalog,
    matchesSearch,
    modTerm,
    tagTerm,
    withAlternative,
    withExclusion,
    type Catalog,
    type CatalogEntry,
    type CatalogKind,
  } from "../../lib/catalog";
  import { onEscape } from "../../lib/escape";
  import { formatAmount } from "../../lib/kinds";
  import { app, deviceName, request } from "../../lib/state.svelte";
  import { posKey, type GlobalPos } from "../../lib/types";

  /**
   * A JEI-like browser for writing filters: pick items, fluids or chemicals (or their tags and mods) from everything
   * the game knows, or from what a device holds right now. Clicking adds to the filter text, which stays editable.
   */
  let {
    value,
    kind: initialKind = "item",
    devices: suggested = [],
    onapply,
    onclose,
  }: {
    value: string;
    kind?: CatalogKind;
    devices?: GlobalPos[];
    onapply: (value: string) => void;
    onclose: () => void;
  } = $props();

  const LIMIT = 400;

  let catalog = $state<Catalog | null>(null);
  let failed = $state(false);
  // The picker starts from what was passed in when it opened; later changes outside don't reset it.
  let text = $state(untrack(() => value));
  let error = $state<string | null>(null);
  let kind = $state<CatalogKind>(untrack(() => initialKind));
  let query = $state("");
  let selected = $state<CatalogEntry | null>(null);
  /** "" browses everything; otherwise a device's position key, to show what it holds. */
  let source = $state(untrack(() => (suggested[0] ? posKey(suggested[0]) : "")));
  let contents = $state<Map<string, number> | null>(null);
  let search: HTMLInputElement | undefined = $state();

  loadCatalog()
    .then((c) => (catalog = c))
    .catch(() => (failed = true));

  $effect(() => {
    search?.focus();
  });

  const kinds = $derived((Object.keys(CATALOG_KIND_LABEL) as CatalogKind[]).filter((k) => k !== "chemical" || (catalog?.chemical.length ?? 0) > 0));
  const allDevices = $derived(app.snapshot?.devices ?? []);
  /** Suggested devices (those feeding this node) first, then the rest by name. */
  const deviceChoices = $derived.by(() => {
    const first = new Set(suggested.map(posKey));
    return [...allDevices].sort((a, b) => {
      const fa = first.has(posKey(a.pos)) ? 0 : 1;
      const fb = first.has(posKey(b.pos)) ? 0 : 1;
      return fa - fb || deviceName(a).localeCompare(deviceName(b));
    });
  });

  // What the chosen device holds, fetched again when the device or resource changes.
  $effect(() => {
    const key = source;
    const k = kind;
    contents = null;
    if (!key) return;
    const device = allDevices.find((d) => posKey(d.pos) === key);
    if (!device) return;
    request("deviceContents", { device: device.pos, resource: k })
      .then((r: { entries: { id: string; amount: number }[] }) => {
        if (source === key && kind === k) contents = new Map(r.entries.map((e) => [e.id, e.amount]));
      })
      .catch(() => {
        if (source === key && kind === k) contents = new Map();
      });
  });

  const matches = $derived.by(() => {
    if (!catalog) return [];
    const words = query.toLowerCase().split(/\s+/).filter(Boolean);
    let list = catalog[kind];
    if (source) {
      if (!contents) return [];
      const known = new Map(list.map((e) => [e.id, e]));
      // Unknown ids (a removed mod, say) still show, by id.
      list = [...contents.keys()].map((id) => known.get(id) ?? { id, name: id, tags: [] });
    }
    return words.length ? list.filter((e) => matchesSearch(e, words)) : list;
  });

  let checkTimer: ReturnType<typeof setTimeout> | undefined;
  $effect(() => {
    const expression = text;
    clearTimeout(checkTimer);
    checkTimer = setTimeout(async () => {
      try {
        const r = await request("checkFilter", { expression, resource: kind });
        if (expression === text) error = r.valid ? null : r.message;
      } catch {
        error = null;
      }
    }, 250);
  });

  function add(term: string, exclude: boolean) {
    text = exclude ? withExclusion(text, term) : withAlternative(text, term);
  }

  /** Alt-click or right-click excludes instead of adding. */
  function termClick(e: MouseEvent, term: string) {
    e.preventDefault();
    add(term, e.altKey || e.button === 2);
  }

  function apply() {
    onapply(text.trim());
    onclose();
  }

  // Esc closes the picker (the innermost thing open).
  $effect(() => onEscape(() => onclose()));
</script>


<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
<div class="fixed inset-0 z-50 flex items-center justify-center p-4" style="background: rgb(0 0 0 / 0.45)" onclick={onclose}>
  <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
  <div
    class="flex max-h-full w-full flex-col gap-2 rounded-lg border p-3"
    style="max-width: 760px; height: 640px; background: var(--panel); border-color: var(--line); box-shadow: 0 8px 30px rgb(0 0 0 / 0.35)"
    onclick={(e) => e.stopPropagation()}
  >
    <div class="flex items-center gap-2">
      <h2 class="flex-1 text-base font-semibold">Pick for filter</h2>
      <button class="btn text-sm" onclick={onclose}>Cancel</button>
      <button class="btn btn-primary text-sm" onclick={apply} disabled={!!error}>Apply</button>
    </div>
    <div class="flex flex-col gap-0.5">
      <label class="flex flex-col gap-0.5">
        <span class="text-xs font-medium muted">Filter — what Apply puts in the box. Built from what you add below; you can also type it.</span>
        <input
          class="w-full font-mono text-sm"
          style={error ? "border-color: var(--bad)" : ""}
          bind:value={text}
          placeholder="empty: matches anything"
          spellcheck="false"
        />
      </label>
      {#if error}<span class="text-xs" style="color: var(--bad)">{error}</span>{/if}
    </div>

    <div class="flex flex-wrap items-center gap-2">
      <div class="flex gap-1">
        {#each kinds as k (k)}
          <button class="btn text-sm" class:btn-primary={kind === k} onclick={() => ((kind = k), (selected = null))}>{CATALOG_KIND_LABEL[k]}</button>
        {/each}
      </div>
      <select class="min-w-0 flex-1 text-sm" bind:value={source} title="Browse everything, or just what one device holds now">
        <option value="">Everything</option>
        {#each deviceChoices as d (posKey(d.pos))}
          <option value={posKey(d.pos)}>In {deviceName(d)}</option>
        {/each}
      </select>
      <input bind:this={search} class="text-sm" style="width: 220px" bind:value={query} placeholder="Search the list (@mod, #tag)" spellcheck="false" />
    </div>

    <div class="flex min-h-0 flex-1 gap-3">
      <div class="min-h-0 flex-1 overflow-y-auto rounded border p-1" style="border-color: var(--line); background: var(--panel-2)">
        {#if failed}
          <p class="p-2 text-sm muted">Couldn't load the list of items from the game.</p>
        {:else if !catalog || (source && !contents)}
          <p class="p-2 text-sm muted">Loading…</p>
        {:else if matches.length === 0}
          <p class="p-2 text-sm muted">{source ? "Nothing matching in that device." : "Nothing matches."}</p>
        {:else}
          <div class="grid gap-0.5" style="grid-template-columns: repeat(auto-fill, minmax(40px, 1fr))">
            {#each matches.slice(0, LIMIT) as entry (entry.id)}
              <button
                class="picker-cell relative flex items-center justify-center rounded"
                class:picked={selected?.id === entry.id}
                title={`${entry.name}\n${entry.id}\nDouble-click to add; alt-click or right-click to exclude`}
                onclick={(e) => {
                  selected = entry;
                  if (e.altKey) add(itemTerm(entry.id), true);
                }}
                ondblclick={() => add(itemTerm(entry.id), false)}
                oncontextmenu={(e) => {
                  e.preventDefault();
                  selected = entry;
                  add(itemTerm(entry.id), true);
                }}
              >
                <img src={iconUrl(kind, entry.id)} alt="" width="32" height="32" loading="lazy" class="icon" />
                {#if contents?.has(entry.id)}
                  <span class="absolute bottom-0 right-0.5 tabular-nums" style="font-size: 9px; text-shadow: 0 0 2px var(--panel)">{formatAmount(contents.get(entry.id) ?? 0, kind)}</span>
                {/if}
              </button>
            {/each}
          </div>
          {#if matches.length > LIMIT}
            <p class="p-2 text-xs muted">Showing {LIMIT} of {matches.length}; search to narrow it down.</p>
          {/if}
        {/if}
      </div>

      <div class="flex w-56 shrink-0 flex-col gap-2 overflow-y-auto text-sm">
        {#if selected}
          {@const entry = selected}
          <div class="flex items-center gap-2">
            <img src={iconUrl(kind, entry.id)} alt="" width="40" height="40" class="icon" />
            <div class="min-w-0">
              <div class="font-medium">{entry.name}</div>
              <div class="truncate font-mono text-xs muted" title={entry.id}>{entry.id}</div>
            </div>
          </div>
          <div class="flex gap-1">
            <button class="btn flex-1 text-sm" onclick={() => add(itemTerm(entry.id), false)}>Add</button>
            <button class="btn flex-1 text-sm" onclick={() => add(itemTerm(entry.id), true)}>Exclude</button>
          </div>
          <div class="flex flex-col gap-1">
            <span class="text-xs font-medium muted">Mod</span>
            <div>
              <button class="chip" onclick={(e) => termClick(e, modTerm(entry.id))} oncontextmenu={(e) => termClick(e, modTerm(entry.id))}>
                {entry.id.slice(0, entry.id.indexOf(":"))}
              </button>
            </div>
          </div>
          {#if entry.tags.length}
            <div class="flex flex-col gap-1">
              <span class="text-xs font-medium muted">Tags</span>
              <div class="flex flex-wrap gap-1">
                {#each entry.tags as tag (tag)}
                  <button class="chip" onclick={(e) => termClick(e, tagTerm(tag))} oncontextmenu={(e) => termClick(e, tagTerm(tag))}>#{tag}</button>
                {/each}
              </div>
            </div>
          {/if}
          <p class="text-xs muted">Click a mod or tag to add it (anything from that mod, or with that tag). Alt-click or right-click excludes it instead.</p>
        {:else}
          <p class="text-xs muted">
            Click something to see its tags and mod. Double-click adds it to the filter; alt-click or right-click excludes it. Added things match
            if any of them does.
          </p>
        {/if}
      </div>
    </div>
  </div>
</div>

<style>
  .picker-cell {
    height: 40px;
    border: 1px solid transparent;
  }
  .picker-cell:hover {
    background: var(--panel);
    border-color: var(--line);
  }
  .picker-cell.picked {
    border-color: var(--accent);
    background: var(--panel);
  }
  .chip {
    border: 1px solid var(--line);
    border-radius: 999px;
    padding: 0 8px;
    font-size: 12px;
    font-family: ui-monospace, monospace;
    background: var(--panel-2);
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .chip:hover {
    border-color: var(--accent);
  }
</style>
