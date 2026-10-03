<script lang="ts">
  import { app, deviceAt, nodeTitle } from "../lib/state.svelte";
  import { blockName, slotName } from "../lib/blueprints";
  import { KIND_ICON } from "../lib/icons";
  import { KIND_LABEL } from "../lib/kinds";
  import { posKey, type GraphNode } from "../lib/types";
  import DeviceIcon from "./DeviceIcon.svelte";

  /**
   * A node as its card's header shows it, small: its icon spanning two lines, its name over what it is (a device's
   * kind of block, a slot, a copy's blueprint, else the kind of node). For naming a node in a message.
   */
  let { node }: { node: GraphNode } = $props();

  const s = $derived(node.settings);
  const inBlueprint = $derived(!!app.snapshot?.sheets.some((x) => x.blueprint && x.nodes?.some((n) => n.id === node.id)));
  const device = $derived(s.kind === "device" ? deviceAt(posKey(s.device)) : undefined);
  const slot = $derived(s.kind === "device" && inBlueprint);
  const name = $derived(slot ? slotName(node) : nodeTitle(node));
  const type = $derived(
    slot
      ? "Slot"
      : s.kind === "device"
        ? blockName(device?.block)
        : s.kind === "copy"
          ? `Copy of ${app.snapshot?.sheets.find((x) => x.id === s.sheet)?.name ?? "a missing blueprint"}`
          : KIND_LABEL[s.kind],
  );
  const KindIcon = $derived(KIND_ICON[s.kind]);
</script>

<span class="chip inline-grid shrink-0 items-center text-left">
  <span class="icon flex">
    {#if slot && s.kind === "device" && s.block}
      <span class="flex" style="opacity: 0.5"><DeviceIcon item={s.block} size={20} /></span>
    {:else if device}
      <DeviceIcon item={device.icon} size={20} />
    {:else}
      <span class="flex muted"><KindIcon size={18} /></span>
    {/if}
  </span>
  <span class="truncate font-medium" style="font-size: 12px; line-height: 1.2">{name}</span>
  <span class="truncate muted" style="font-size: 10px; line-height: 1.2">{type}</span>
</span>

<style>
  /* Icon in the first column across both lines; name over type in the second. */
  .chip {
    grid-template-columns: auto minmax(0, 1fr);
    column-gap: 6px;
    max-width: 14rem;
  }
  .icon {
    grid-row: span 2;
  }
</style>
