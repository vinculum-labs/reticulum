<script lang="ts">
  import { untrack, type Snippet } from "svelte";
  import { CATALOG_KIND_LABEL, iconUrl, loadCatalog, matchesSearch, type Catalog, type CatalogEntry, type CatalogKind } from "../../lib/catalog";
  import Segmented from "../controls/Segmented.svelte";
  import Select from "../controls/Select.svelte";
  import { formatAmount } from "../../lib/kinds";
  import { app, deviceName, request } from "../../lib/state.svelte";
  import { posKey, type GlobalPos } from "../../lib/types";

  /**
   * A JEI-like browser of everything the game knows (items, fluids, chemicals), or of what one device holds now: a
   * search (by name or id, "@mod", "#tag"), tabs for each kind, a grid of icons and a column of details on the one
   * selected. The filter picker and the pick-one dialog are built on it: they say what clicks do and what the details
   * column offers.
   */
  let {
    kinds: offered,
    kind = $bindable(),
    selected = $bindable(null),
    query = $bindable(""),
    devices: suggested = [],
    omit,
    browseDevices = true,
    cellTitle,
    onclick,
    ondblclick,
    oncontextmenu,
    details,
    hint,
  }: {
    /** The kinds to browse (tabs when more than one; chemicals only when the game has any). */
    kinds: CatalogKind[];
    kind: CatalogKind;
    selected?: CatalogEntry | null;
    /** What is searched for. */
    query?: string;
    /** Entries not offered (already picked elsewhere, say). */
    omit?: (kind: CatalogKind, id: string) => boolean;
    /** Devices offered first when browsing what a device holds (those feeding a node, say). */
    devices?: GlobalPos[];
    /** Whether "In <device>" can be browsed as well as everything. */
    browseDevices?: boolean;
    /** A cell's tooltip after its name and id (what clicking it does). */
    cellTitle?: string;
    onclick?: (e: MouseEvent, entry: CatalogEntry) => void;
    ondblclick?: (e: MouseEvent, entry: CatalogEntry) => void;
    oncontextmenu?: (e: MouseEvent, entry: CatalogEntry) => void;
    /** The details column for the selected entry. */
    details: Snippet<[CatalogEntry]>;
    /** The details column with nothing selected. */
    hint?: Snippet;
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

  let catalog = $state<Catalog | null>(null);
  let failed = $state(false);
  /** "" browses everything; otherwise a device's position key, to show what it holds. */
  let source = $state(untrack(() => (browseDevices && suggested[0] ? posKey(suggested[0]) : "")));
  let contents = $state<Map<string, number> | null>(null);
  let search: HTMLInputElement | undefined = $state();

  loadCatalog()
    .then((c) => (catalog = c))
    .catch(() => (failed = true));

  $effect(() => {
    search?.focus();
  });

  // Back to the top whenever what is listed changes.
  $effect(() => {
    void query;
    void kind;
    void source;
    if (scroller) scroller.scrollTop = 0;
    scrollTop = 0;
  });

  const kinds = $derived(offered.filter((k) => k !== "chemical" || (catalog?.chemical.length ?? 0) > 0));
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
    if (omit) list = list.filter((e) => !omit(kind, e.id));
    return words.length ? list.filter((e) => matchesSearch(e, words)) : list;
  });
</script>

<div class="flex min-h-0 flex-1 flex-col gap-2">
  <!-- Same two columns as below: search and tabs over the grid, the source picker over the details column. -->
  <div class="flex items-center gap-3">
    <div class="flex min-w-0 flex-1 items-center gap-2">
      <input bind:this={search} class="min-w-0 flex-1 text-sm" bind:value={query} placeholder="Search (name, id, @mod, #tag)" spellcheck="false" />
      {#if kinds.length > 1}
        <Segmented label="List" value={kind} options={kinds.map((k) => ({ value: k, label: CATALOG_KIND_LABEL[k] }))} onchange={(k) => ((kind = k), (selected = null))} />
      {/if}
    </div>
    {#if browseDevices}
      <Select
        class="w-56 shrink-0 text-sm"
        bind:value={source}
        title="Browse everything, or just what one device holds now"
        options={[{ value: "", label: "Everything" }, ...deviceChoices.map((d) => ({ value: posKey(d.pos), label: `In ${deviceName(d)}` }))]}
      />
    {/if}
  </div>

  <div class="flex min-h-0 flex-1 gap-3">
    <div
      bind:this={scroller}
      bind:clientWidth={viewWidth}
      bind:clientHeight={viewHeight}
      onscroll={() => (scrollTop = scroller?.scrollTop ?? 0)}
      class="min-h-0 flex-1 overflow-y-auto rounded border border-line bg-panel-2 p-1"
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
          <div class="absolute inset-x-0 grid gap-0.5" style="top: {first * ROW}px; grid-template-columns: repeat({columns}, minmax({CELL}px, 1fr))">
            {#each matches.slice(first * columns, last * columns) as entry (entry.id)}
              <button
                class="picker-cell relative flex items-center justify-center rounded"
                class:picked={selected?.id === entry.id}
                title={`${entry.name}\n${entry.id}${cellTitle ? `\n${cellTitle}` : ""}`}
                onclick={(e) => {
                  selected = entry;
                  onclick?.(e, entry);
                }}
                ondblclick={(e) => ondblclick?.(e, entry)}
                oncontextmenu={(e) => {
                  if (!oncontextmenu) return;
                  e.preventDefault();
                  selected = entry;
                  oncontextmenu(e, entry);
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
        {@render details(selected)}
      {:else if hint}
        {@render hint()}
      {/if}
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
</style>
