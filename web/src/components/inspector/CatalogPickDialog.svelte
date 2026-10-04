<script lang="ts">
  import { untrack } from "svelte";
  import { loadCatalog, type CatalogEntry, type CatalogKind } from "../../lib/catalog";
  import Modal, { LARGE_HEIGHT, LARGE_WIDTH } from "../Modal.svelte";
  import CatalogBrowser from "./CatalogBrowser.svelte";
  import CatalogEntryHeader from "./CatalogEntryHeader.svelte";

  /**
   * Picks one item, fluid or chemical from everything the game knows (or what a device holds): click to see it,
   * double-click (or Choose) to pick it.
   */
  let {
    title,
    kinds,
    kind: initialKind,
    value,
    browseDevices = true,
    omit,
    onpick,
    onclose,
  }: {
    title: string;
    /** What may be picked: one kind, or tabs for several. */
    kinds: CatalogKind[];
    /** The kind shown first (the current pick's, say). */
    kind?: CatalogKind;
    /** The current pick's id, selected to begin with when it is of the first kind shown. */
    value?: string;
    /** Whether what a device holds can be browsed as well as everything. */
    browseDevices?: boolean;
    /** Entries not offered (already picked elsewhere, say). */
    omit?: (kind: CatalogKind, id: string) => boolean;
    onpick: (pick: { kind: CatalogKind; id: string }) => void;
    onclose: () => void;
  } = $props();

  let kind = $state<CatalogKind>(untrack(() => initialKind ?? kinds[0]));
  let query = $state("");
  let selected = $state<CatalogEntry | null>(untrack(() => (value ? { id: value, name: value, tags: [] } : null)));
  // The current pick by its name and tags, once the catalog is in.
  loadCatalog()
    .then((c) => {
      if (selected && selected.name === selected.id) selected = c[kind].find((e) => e.id === selected!.id) ?? selected;
    })
    .catch(() => {});

  function modOf(id: string): string {
    return id.slice(0, id.indexOf(":"));
  }

  function pick(entry: CatalogEntry | null) {
    if (!entry) return;
    onpick({ kind, id: entry.id });
    onclose();
  }
</script>

<!-- Esc or a click outside closes it, like Cancel. -->
<Modal {onclose} width={LARGE_WIDTH} height={LARGE_HEIGHT} resizable minWidth={560} minHeight={360} {title}>
  {#snippet footer()}
    <button class="btn" onclick={onclose}>Cancel</button>
    <button class="btn btn-primary" onclick={() => pick(selected)} disabled={!selected}>Choose</button>
  {/snippet}
  <CatalogBrowser {kinds} bind:kind bind:selected bind:query {browseDevices} {omit} cellTitle="Double-click to choose" ondblclick={(_e, entry) => pick(entry)}>
    {#snippet details(entry)}
      <CatalogEntryHeader {entry} {kind} />
      <div class="flex flex-col gap-1">
        <span class="text-xs font-medium muted">Mod</span>
        <div>
          <button class="tag" title="Show everything from this mod" onclick={() => (query = `@${modOf(entry.id)}`)}>{modOf(entry.id)}</button>
        </div>
      </div>
      {#if entry.tags.length}
        <div class="flex flex-col gap-1">
          <span class="text-xs font-medium muted">Tags</span>
          <div class="flex flex-wrap gap-1">
            {#each entry.tags as tag (tag)}<button class="tag" title="Show everything with this tag" onclick={() => (query = `#${tag}`)}>#{tag}</button>{/each}
          </div>
        </div>
      {/if}
    {/snippet}
    {#snippet hint()}
      <p class="text-xs muted">Click something to see it, then Choose — or double-click it. Search by name or id, "@mod" for a mod's things, "#tag" for a tag's.</p>
    {/snippet}
  </CatalogBrowser>
</Modal>

<style>
  .tag {
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
  .tag:hover {
    border-color: var(--accent);
  }
</style>
