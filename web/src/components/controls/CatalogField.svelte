<script lang="ts">
  import Plus from "@lucide/svelte/icons/plus";
  import { iconUrl, loadCatalog, type Catalog, type CatalogKind } from "../../lib/catalog";
  import CatalogPickDialog from "../inspector/CatalogPickDialog.svelte";
  import RemoveButton from "./RemoveButton.svelte";

  /**
   * One item, fluid or chemical as a slot (its icon, or empty) with its name beside it: clicking the slot opens the
   * catalog to pick another; with {@code onclear}, an × empties it.
   */
  let {
    kinds,
    kind,
    value,
    empty = "None",
    title = "Choose",
    disabled = false,
    browseDevices = true,
    omit,
    onpick,
    onclear,
  }: {
    kinds: CatalogKind[];
    /** The current pick's kind (the first of {@code kinds} if left out). */
    kind?: CatalogKind;
    /** The current pick's id, or "" / undefined for none. */
    value?: string;
    /** Beside an empty slot. */
    empty?: string;
    /** The dialog's title (and the slot's tooltip). */
    title?: string;
    disabled?: boolean;
    browseDevices?: boolean;
    omit?: (kind: CatalogKind, id: string) => boolean;
    onpick: (pick: { kind: CatalogKind; id: string }) => void;
    onclear?: () => void;
  } = $props();

  let open = $state(false);
  let catalog = $state<Catalog | null>(null);
  loadCatalog().then((c) => (catalog = c)).catch(() => {});
  const shownKind = $derived(kind ?? kinds[0]);
  const picked = $derived(value ? (catalog?.[shownKind].find((e) => e.id === value) ?? { id: value, name: value, tags: [] }) : undefined);
</script>

<div class="flex items-center gap-2">
  <button type="button" class="slot" {disabled} title={`${title}…`} aria-label={`${title}${picked ? ` (now ${picked.name})` : ""}`} onclick={() => (open = true)}>
    {#if picked}
      <img src={iconUrl(shownKind, picked.id)} alt="" width="24" height="24" style="image-rendering: pixelated" />
    {:else}
      <Plus size={14} class="muted" />
    {/if}
  </button>
  <div class="flex min-w-0 flex-1 flex-col">
    {#if picked}
      <span class="truncate">{picked.name}</span>
      <span class="tail truncate text-xs muted" title={picked.id}>&lrm;{picked.id}&lrm;</span>
    {:else}
      <span class="truncate muted">{empty}</span>
    {/if}
  </div>
  {#if picked && onclear && !disabled}
    <RemoveButton label="Remove" onclick={onclear} />
  {/if}
</div>
{#if open}
  <CatalogPickDialog {title} {kinds} kind={shownKind} {value} {browseDevices} {omit} {onpick} onclose={() => (open = false)} />
{/if}

<style>
  /* Like an inventory slot: a sunken square holding the item. */
  .slot {
    width: 36px;
    height: 36px;
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 4px;
    background: var(--panel-2);
    border: 1px solid var(--line);
    box-shadow: inset 0 1px 3px rgb(0 0 0 / 0.35);
  }
  .slot:hover:not(:disabled) {
    border-color: var(--accent);
  }
  .slot:disabled {
    opacity: 0.5;
  }
  /* An id cut from its start ("…:polished_andesite_stairs"): what it is matters more than its mod. */
  .tail {
    direction: rtl;
    text-align: left;
  }
</style>
