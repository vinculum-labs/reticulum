<script lang="ts">
  import ChevronDown from "@lucide/svelte/icons/chevron-down";
  import { onMount } from "svelte";
  import { deviceName, matchesDevice } from "../../lib/state.svelte";
  import { ListNav } from "../../lib/listnav.svelte";
  import { loadCatalog, matchesSearch, type CatalogEntry } from "../../lib/catalog";
  import { blockName } from "../../lib/blueprints";
  import { posKey, type Device } from "../../lib/types";
  import Popover from "../controls/Popover.svelte";
  import { fieldId } from "../controls/field";
  import DeviceIcon from "../DeviceIcon.svelte";
  import type { Snippet } from "svelte";

  /**
   * Chooses the kind of block a blueprint's slot takes: from a device on this network (which also gives the slot a
   * place, for filling instances by layout), or any block from the game's catalog (a kind only). A button showing the
   * current kind, opening a searchable list: the network's devices first, then every block. Only the rows in view
   * are built. {@code button} replaces the field-like button (the toolbar's Slot).
   */
  let {
    devices,
    block,
    from,
    placeholder = "Choose a kind of block",
    disabled = false,
    button,
    onchange,
  }: {
    devices: Device[];
    /** The current kind ("minecraft:barrel"). */
    block?: string;
    /** The device it was taken from, when it was. */
    from?: Device;
    placeholder?: string;
    disabled?: boolean;
    button?: Snippet<[{ open: () => void }]>;
    onchange: (pick: { block: string; device?: Device }) => void;
  } = $props();

  const ROW = 40;
  const id = fieldId();

  let open = $state(false);
  let query = $state("");
  let trigger: HTMLElement | undefined = $state();
  let list: HTMLDivElement | undefined = $state();
  let popover: Popover | undefined = $state();
  let scrollTop = $state(0);
  let viewHeight = $state(288);
  let catalog = $state<CatalogEntry[] | null>(null);
  let catalogFailed = $state(false);

  onMount(() => {
    loadCatalog()
      .then((c) => (catalog = c.block ?? []))
      .catch(() => (catalogFailed = true));
  });

  type Row = { kind: "header"; text: string } | { kind: "device"; device: Device } | { kind: "block"; entry: CatalogEntry };

  const rows = $derived.by(() => {
    const words = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
    const out: Row[] = [];
    const here = devices.filter((d) => d.state === "active" && matchesDevice(d, query)).sort((a, b) => deviceName(a).localeCompare(deviceName(b)));
    if (here.length) {
      out.push({ kind: "header", text: "Devices on this network" });
      for (const device of here) out.push({ kind: "device", device });
    }
    const blocks = (catalog ?? []).filter((e) => matchesSearch(e, words)).sort((a, b) => a.name.localeCompare(b.name));
    if (blocks.length) {
      out.push({ kind: "header", text: "All blocks" });
      for (const entry of blocks) out.push({ kind: "block", entry });
    }
    return out;
  });
  // Headings aren't rows to pick; the list builds only the rows in view, so they are kept in view by their height.
  const nav = new ListNav(() => rows.length, () => list, { selectable: (i) => rows[i]?.kind !== "header", rowHeight: ROW });

  const first = $derived(Math.max(0, Math.floor(scrollTop / ROW) - 4));
  const last = $derived(Math.min(rows.length, Math.ceil((scrollTop + viewHeight) / ROW) + 4));

  function show() {
    if (disabled) return;
    query = "";
    scrollTop = 0;
    open = true;
    nav.set(Math.max(0, rows.findIndex((r) => r.kind !== "header")));
  }

  /** Opens the list from outside (a right-click menu's Slot…). */
  export function openList() {
    show();
  }

  function pick(row: Row | undefined) {
    if (!row || row.kind === "header") return;
    popover?.close(true);
    if (row.kind === "device") onchange({ block: row.device.block, device: row.device });
    else onchange({ block: row.entry.id });
  }

  function onkeydown(e: KeyboardEvent) {
    nav.key(e, (i) => pick(rows[i]));
  }
</script>

{#if button}
  <span bind:this={trigger} class="contents">{@render button({ open: () => (open ? popover?.close(false) : show()) })}</span>
{:else}
  <button
    bind:this={trigger}
    type="button"
    {id}
    class="flex w-full items-center gap-2 rounded-md border border-line bg-field px-2 py-1 text-left"
    {disabled}
    onclick={() => (open ? popover?.close(false) : show())}
    aria-haspopup="listbox"
    aria-expanded={open}
  >
    <DeviceIcon item={block} size={18} />
    <span class="min-w-0 flex-1 truncate" class:muted={!block}>
      {block ? blockName(block) : placeholder}{#if from}<span class="muted"> · from {deviceName(from)}</span>{/if}
    </span>
    <ChevronDown size={14} class="muted" />
  </button>
{/if}

<Popover bind:this={popover} bind:open anchor={trigger?.firstElementChild instanceof HTMLElement && button ? trigger.firstElementChild : trigger} width={280} label="Kinds of block">
  <!-- svelte-ignore a11y_autofocus -->
  <input
    bind:value={query}
    class="m-1 w-[calc(100%-0.5rem)]"
    placeholder="Search blocks, devices, @mod or #tag"
    autofocus
    oninput={() => {
      nav.set(Math.max(0, rows.findIndex((r) => r.kind !== "header")));
      if (list) list.scrollTop = 0;
    }}
    {onkeydown}
  />
  <div
    bind:this={list}
    bind:clientHeight={viewHeight}
    class="min-h-0 flex-1 overflow-y-auto"
    style="max-height: 20rem"
    role="listbox"
    aria-label="Kinds of block"
    onscroll={() => (scrollTop = list?.scrollTop ?? 0)}
  >
    {#if rows.length === 0}
      <p class="px-2 py-1 text-sm muted">{catalog === null && !catalogFailed ? "Loading blocks…" : "Nothing matches."}</p>
    {:else}
      <div class="relative" style="height: {rows.length * ROW}px">
        <div class="absolute inset-x-0" style="top: {first * ROW}px">
          {#each rows.slice(first, last) as row, j (row.kind === "header" ? "h:" + row.text : row.kind === "device" ? "d:" + posKey(row.device.pos) : "b:" + row.entry.id)}
            {@const i = first + j}
            {#if row.kind === "header"}
              <div class="field-label flex items-end px-2 pb-1" style="height: {ROW}px">{row.text}</div>
            {:else}
              <button
                type="button"
                role="option"
                aria-selected={i === nav.active}
                data-index={i}
                class="list-row flex w-full items-center gap-2 px-2 text-left"
                class:active={i === nav.active}
                style="height: {ROW}px"
                tabindex="-1"
                onpointerenter={() => (nav.active = i)}
                onclick={() => pick(row)}
              >
                {#if row.kind === "device"}
                  <DeviceIcon item={row.device.icon} size={18} />
                  <span class="min-w-0 flex-1">
                    <span class="block truncate">{deviceName(row.device)}</span>
                    <span class="block truncate text-xs muted">{blockName(row.device.block)} · {row.device.pos.pos.join(", ")}</span>
                  </span>
                {:else}
                  <DeviceIcon item={row.entry.id} size={18} />
                  <span class="min-w-0 flex-1">
                    <span class="block truncate">{row.entry.name}</span>
                    <span class="block truncate text-xs muted">{row.entry.id}</span>
                  </span>
                {/if}
              </button>
            {/if}
          {/each}
        </div>
      </div>
    {/if}
  </div>
</Popover>
