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
  import Modal, { LARGE_HEIGHT, LARGE_WIDTH } from "../Modal.svelte";
  import Segmented from "../controls/Segmented.svelte";
  import FilterInput from "./FilterInput.svelte";
  import { formatAmount } from "../../lib/kinds";
  import { app, deviceName, request } from "../../lib/state.svelte";
  import { posKey, type GlobalPos } from "../../lib/types";

  /**
   * A JEI-like browser for writing filters: pick items, fluids or chemicals (or their tags and mods) from everything
   * the game knows, or from what a device holds right now. Clicking includes or excludes things in the filter text, which stays editable.
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

  /*
   * The grid is virtual: it is as tall as every match needs from the start, so the scrollbar keeps its size and
   * place, but only the rows in view (and a few either side) are built, so a huge modpack stays quick.
   */
  const CELL = 40;
  const GAP = 2;
  const ROW = CELL + GAP;
  /** The grid's padding (p-1). */
  const PAD = 4;
  /** Rows built beyond each edge of the view. */
  const OVERSCAN = 3;
  let scroller: HTMLDivElement | undefined = $state();
  let scrollTop = $state(0);
  let viewWidth = $state(0);
  let viewHeight = $state(0);

  const columns = $derived(Math.max(1, Math.floor((viewWidth - 2 * PAD + GAP) / ROW)));

  // Back to the top whenever what is listed changes.
  $effect(() => {
    void query;
    void kind;
    void source;
    if (scroller) scroller.scrollTop = 0;
    scrollTop = 0;
  });

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


  function include(term: string, exclude: boolean) {
    text = exclude ? withExclusion(text, term) : withAlternative(text, term);
  }

  /** Alt-click or right-click excludes instead of including. */
  function termClick(e: MouseEvent, term: string) {
    e.preventDefault();
    include(term, e.altKey || e.button === 2);
  }

  function apply() {
    onapply(text.trim());
    onclose();
  }

</script>

<!-- Esc or a click outside closes it, like Cancel. -->
<Modal {onclose} width={LARGE_WIDTH} height={LARGE_HEIGHT} resizable minWidth={560} minHeight={360} title="Pick for filter">
  {#snippet footer()}
    <button class="btn" onclick={onclose}>Cancel</button>
    <button class="btn btn-primary" onclick={apply} disabled={!!error}>Apply</button>
  {/snippet}
  <div class="flex min-h-0 flex-1 flex-col gap-2">
    <FilterInput bind:text bind:error kind={initialKind} placeholder="empty: matches anything" />

    <!-- Same two columns as below: search and tabs over the grid, the source picker over the details panel. -->
    <div class="flex items-center gap-3">
      <div class="flex min-w-0 flex-1 items-center gap-2">
        <input bind:this={search} class="min-w-0 flex-1 text-sm" bind:value={query} placeholder="Search the list (@mod, #tag)" spellcheck="false" />
        <Segmented
          label="List"
          value={kind}
          options={kinds.map((k) => ({ value: k, label: CATALOG_KIND_LABEL[k] }))}
          onchange={(k) => ((kind = k), (selected = null))}
        />
      </div>
      <select class="w-56 shrink-0 text-sm" bind:value={source} title="Browse everything, or just what one device holds now">
        <option value="">Everything</option>
        {#each deviceChoices as d (posKey(d.pos))}
          <option value={posKey(d.pos)}>In {deviceName(d)}</option>
        {/each}
      </select>
    </div>

    <div class="flex min-h-0 flex-1 gap-3">
      <div
        bind:this={scroller}
        bind:clientWidth={viewWidth}
        bind:clientHeight={viewHeight}
        onscroll={() => (scrollTop = scroller?.scrollTop ?? 0)}
        class="min-h-0 flex-1 overflow-y-auto rounded border p-1"
        style="border-color: var(--line); background: var(--panel-2)"
      >
        {#if failed}
          <p class="p-2 text-sm muted">Couldn't load the list of items from the game.</p>
        {:else if !catalog || (source && !contents)}
          <p class="p-2 text-sm muted">Loading…</p>
        {:else if matches.length === 0}
          <p class="p-2 text-sm muted">{source ? "Nothing matching in that device." : "Nothing matches."}</p>
        {:else}
          {@const rows = Math.ceil(matches.length / columns)}
          {@const first = Math.max(0, Math.floor((scrollTop - PAD) / ROW) - OVERSCAN)}
          {@const last = Math.min(rows, Math.ceil((scrollTop + viewHeight) / ROW) + OVERSCAN)}
          <div class="relative" style="height: {rows * ROW - GAP}px">
            <div
              class="absolute inset-x-0 grid gap-0.5"
              style="top: {first * ROW}px; grid-template-columns: repeat({columns}, minmax({CELL}px, 1fr))"
            >
              {#each matches.slice(first * columns, last * columns) as entry (entry.id)}
                <button
                  class="picker-cell relative flex items-center justify-center rounded"
                  class:picked={selected?.id === entry.id}
                  title={`${entry.name}\n${entry.id}\nDouble-click to include; alt-click or right-click to exclude`}
                  onclick={(e) => {
                    selected = entry;
                    if (e.altKey) include(itemTerm(entry.id), true);
                  }}
                  ondblclick={() => include(itemTerm(entry.id), false)}
                  oncontextmenu={(e) => {
                    e.preventDefault();
                    selected = entry;
                    include(itemTerm(entry.id), true);
                  }}
                >
                  <img src={iconUrl(kind, entry.id)} alt="" width="32" height="32" loading="lazy" class="icon" />
                  {#if contents?.has(entry.id)}
                    <span class="absolute bottom-0 right-0.5 tabular-nums" style="font-size: 9px; text-shadow: 0 0 2px var(--panel)">{formatAmount(contents.get(entry.id) ?? 0, kind)}</span>
                  {/if}
                </button>
              {/each}
            </div>
          </div>
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
            <button class="btn flex-1 text-sm" onclick={() => include(itemTerm(entry.id), false)}>Include</button>
            <button class="btn flex-1 text-sm" onclick={() => include(itemTerm(entry.id), true)}>Exclude</button>
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
          <p class="text-xs muted">Click a mod or tag to include it (anything from that mod, or with that tag). Alt-click or right-click excludes it instead.</p>
        {:else}
          <p class="text-xs muted">
            Click something to see its tags and mod. Double-click includes it in the filter; alt-click or right-click excludes it. Included things match
            if any of them does.
          </p>
        {/if}
      </div>
    </div>
  </div>
</Modal>

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
