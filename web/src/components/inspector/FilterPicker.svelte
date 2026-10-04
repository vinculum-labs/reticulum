<script lang="ts">
  import { untrack } from "svelte";
  import { CATALOG_KIND_LABEL, itemTerm, modTerm, tagTerm, withAlternative, withExclusion, type CatalogEntry, type CatalogKind } from "../../lib/catalog";
  import Modal, { LARGE_HEIGHT, LARGE_WIDTH } from "../Modal.svelte";
  import FilterInput from "./FilterInput.svelte";
  import CatalogBrowser from "./CatalogBrowser.svelte";
  import CatalogEntryHeader from "./CatalogEntryHeader.svelte";
  import type { GlobalPos } from "../../lib/types";

  /**
   * A JEI-like browser for writing filters: pick items, fluids or chemicals (or their tags and mods) from everything
   * the game knows, or from what a device holds right now. Clicking includes or excludes things in the filter text, which stays editable.
   */
  let {
    value,
    kind: initialKind = "item",
    devices = [],
    onapply,
    onclose,
  }: {
    value: string;
    kind?: CatalogKind;
    devices?: GlobalPos[];
    onapply: (value: string) => void;
    onclose: () => void;
  } = $props();

  // The picker starts from what was passed in when it opened; later changes outside don't reset it.
  let text = $state(untrack(() => value));
  let error = $state<string | null>(null);
  let kind = $state<CatalogKind>(untrack(() => initialKind));
  let selected = $state<CatalogEntry | null>(null);

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
    <CatalogBrowser
      kinds={Object.keys(CATALOG_KIND_LABEL) as CatalogKind[]}
      bind:kind
      bind:selected
      {devices}
      cellTitle="Double-click to include — alt-click or right-click to exclude"
      onclick={(e, entry) => e.altKey && include(itemTerm(entry.id), true)}
      ondblclick={(_e, entry) => include(itemTerm(entry.id), false)}
      oncontextmenu={(_e, entry) => include(itemTerm(entry.id), true)}
    >
      {#snippet details(entry)}
        <CatalogEntryHeader {entry} {kind} />
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
      {/snippet}
      {#snippet hint()}
        <p class="text-xs muted">
          Click something to see its tags and mod. Double-click includes it in the filter; alt-click or right-click excludes it. Included things match
          if any of them does.
        </p>
      {/snippet}
    </CatalogBrowser>
  </div>
</Modal>

<style>
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
