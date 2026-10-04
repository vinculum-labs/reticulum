<script lang="ts">
  import { KIND_ICON, SparklesIcon, SplineIcon, ZapIcon } from "../../lib/icons";
  import { KIND_HINT, KIND_LABEL, SIGNAL_KINDS } from "../../lib/kinds";
  import { app, setWireStyle, WIRE_STYLE_LABEL, type WireStyle } from "../../lib/state.svelte";
  import type { Device, NodeKind, Sheet } from "../../lib/types";
  import BlockPicker from "../inspector/BlockPicker.svelte";
  import MenuButton from "./MenuButton.svelte";

  /**
   * The canvas's toolbar: a button per kind of node (added mid-view), grouped by thin dividers, not colors (a color
   * means what flows): nodes that move things (Redstone too) | nodes that hold others | the canvas's own settings.
   */
  let {
    sheet,
    tidying,
    onadd,
    onslot,
    ontidy,
  }: {
    sheet: Sheet | null;
    tidying: boolean;
    onadd: (kind: NodeKind) => void;
    /**
     * A slot picked on a blueprint: a kind of block, from a device here or the catalog. {@code at} is where a
     * right-click's Slot… asked for it; null from the toolbar's button (mid-view).
     */
    onslot: (pick: { block: string; device?: Device }, at: { x: number; y: number } | null) => void;
    ontidy: () => void;
  } = $props();

  let slotPicker: BlockPicker | undefined = $state();
  let slotAt: { x: number; y: number } | null = null;

  /** Opens the slot picker for a right-click's Slot…, to put the slot there. */
  export function pickSlot(at: { x: number; y: number }) {
    slotAt = at;
    slotPicker?.openList();
  }

  const DeviceKindIcon = KIND_ICON.device;
  const movers: NodeKind[] = ["device", "filter", "rate_limit", "splitter", "kit"];
  /** Blueprints that can be placed here (as instances): not this sheet. */
  const blueprints = $derived((app.snapshot?.sheets ?? []).filter((x) => x.blueprint && x.id !== sheet?.id));
  const holders = $derived<NodeKind[]>([
    // Sheets go inside sheets in the tree, never inside a blueprint.
    ...(sheet?.blueprint ? [] : (["sheet"] as NodeKind[])),
    ...(blueprints.length ? (["instance"] as NodeKind[]) : []),
  ]);
</script>

<div class="flex flex-wrap gap-1 rounded-lg border border-line bg-panel p-1">
  {#snippet addButton(kind: NodeKind)}
    {@const Icon = KIND_ICON[kind]}
    <button class="btn flex items-center gap-1 text-sm" title={KIND_HINT[kind]} onclick={() => onadd(kind)}>
      <Icon size={14} />{KIND_LABEL[kind]}
    </button>
  {/snippet}
  {#each movers as kind (kind)}
    {#if kind === "device" && sheet?.blueprint}
      <!-- On a blueprint a device is a slot: the kind of block it takes, from a device here or the catalog. -->
      <BlockPicker bind:this={slotPicker} devices={app.snapshot?.devices ?? []} onchange={(pick) => onslot(pick, slotAt)}>
        {#snippet button({ open })}
          <button class="btn flex items-center gap-1 text-sm" title="A slot: a kind of block, filled with one wherever the blueprint is placed" onclick={() => ((slotAt = null), open())}>
            <DeviceKindIcon size={14} />Slot
          </button>
        {/snippet}
      </BlockPicker>
    {:else}
      {@render addButton(kind)}
    {/if}
  {/each}
  <MenuButton
    label="Redstone"
    icon={ZapIcon}
    color="var(--c-signal)"
    items={SIGNAL_KINDS.map((kind) => ({ key: kind, label: KIND_LABEL[kind], hint: KIND_HINT[kind], icon: KIND_ICON[kind] }))}
    onpick={(kind) => onadd(kind as NodeKind)}
  />
  <span class="toolbar-divider" aria-hidden="true"></span>
  {#if holders.length}
    {#each holders as kind (kind)}{@render addButton(kind)}{/each}
    <span class="toolbar-divider" aria-hidden="true"></span>
  {/if}
  <!-- Wire style: a canvas setting, remembered in this browser. -->
  <MenuButton
    label={WIRE_STYLE_LABEL[app.wireStyle]}
    icon={SplineIcon}
    width={140}
    title="How wires are drawn between pins (remembered in this browser)"
    items={(Object.keys(WIRE_STYLE_LABEL) as WireStyle[]).map((style) => ({
      key: style,
      label: WIRE_STYLE_LABEL[style],
      checked: style === app.wireStyle,
    }))}
    onpick={(style) => setWireStyle(style as WireStyle)}
  />
  <button class="btn flex items-center gap-1 text-sm" title="Lay the sheet out left to right" disabled={tidying || !sheet?.nodes?.length} onclick={ontidy}>
    <SparklesIcon size={14} />Tidy up
  </button>
</div>

<style>
  .toolbar-divider {
    align-self: stretch;
    width: 1px;
    margin: 2px 2px;
    background: var(--line);
  }
</style>
