<script lang="ts">
  import ChevronDown from "@lucide/svelte/icons/chevron-down";
  import { iconUrl, loadCatalog, type Catalog, type CatalogKind } from "../../lib/catalog";
  import CatalogPickDialog from "../inspector/CatalogPickDialog.svelte";

  /**
   * A field showing one item, fluid or chemical (icon, name, id), or a placeholder; clicking it opens the catalog to
   * pick another.
   */
  let {
    kinds,
    kind,
    value,
    placeholder = "Choose…",
    title = "Choose",
    disabled = false,
    browseDevices = true,
    omit,
    onpick,
  }: {
    kinds: CatalogKind[];
    /** The current pick's kind (the first of {@code kinds} if left out). */
    kind?: CatalogKind;
    /** The current pick's id, or "" / undefined for none. */
    value?: string;
    placeholder?: string;
    /** The dialog's title. */
    title?: string;
    disabled?: boolean;
    browseDevices?: boolean;
    omit?: (kind: CatalogKind, id: string) => boolean;
    onpick: (pick: { kind: CatalogKind; id: string }) => void;
  } = $props();

  let open = $state(false);
  let catalog = $state<Catalog | null>(null);
  loadCatalog().then((c) => (catalog = c)).catch(() => {});
  const shownKind = $derived(kind ?? kinds[0]);
  const picked = $derived(value ? (catalog?.[shownKind].find((e) => e.id === value) ?? { id: value, name: value, tags: [] }) : undefined);
</script>

<button type="button" class="select flex w-full items-center gap-2 text-left" {disabled} onclick={() => (open = true)} aria-haspopup="dialog">
  {#if picked}
    <img src={iconUrl(shownKind, picked.id)} alt="" width="16" height="16" class="shrink-0" style="image-rendering: pixelated" />
    <span class="min-w-0 flex-1 truncate">{picked.name}</span>
    <span class="tail min-w-0 max-w-[40%] truncate text-xs muted" title={picked.id}>&lrm;{picked.id}&lrm;</span>
  {:else}
    <span class="min-w-0 flex-1 truncate muted">{placeholder}</span>
  {/if}
  <ChevronDown size={14} class="shrink-0 muted" />
</button>
{#if open}
  <CatalogPickDialog {title} {kinds} kind={shownKind} {value} {browseDevices} {omit} {onpick} onclose={() => (open = false)} />
{/if}

<style>
  /* Framed like a dropdown (Select's look). */
  .select {
    background: var(--field);
    border: 1px solid var(--line);
    border-radius: 6px;
    padding: 3px 7px;
  }
  .select:disabled {
    opacity: 0.5;
  }
  /* An id cut from its start ("…:polished_andesite_stairs"): what it is matters more than its mod. */
  .tail {
    direction: rtl;
    text-align: left;
  }
</style>
