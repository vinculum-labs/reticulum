<script lang="ts">
  import TriangleAlert from "@lucide/svelte/icons/triangle-alert";
  import { Handle, Position } from "@xyflow/svelte";
  import type { NodeData } from "../../lib/graph";
  import { PIN_DOT, PIN_H, PIN_KNOB, PIN_W } from "../../lib/graph";
  import { RESOURCE_LABEL, RESOURCE_VAR } from "../../lib/kinds";

  /**
   * A sheet's pin, anchored on its frame's edge: the dot sits on the border (inputs left, outputs right) and its name
   * just inside, above the wire. In an open sheet node the frame's own handle is the dot's outer half (wires from
   * outside), this the inner half (wires to what is inside); on the open sheet's frame, with nothing outside, it is a
   * whole dot on the line. Drag it up or down to reorder; select it to name it.
   */
  let { data, selected }: { data: NodeData & { pinSide?: "frame" | "sheet" }; selected?: boolean } = $props();

  const s = $derived(data.node.settings);
  const input = $derived(s.kind === "sheet_input");
  const resource = $derived(s.kind === "sheet_input" || s.kind === "sheet_output" ? s.resource : undefined);
  const name = $derived((s.kind === "sheet_input" || s.kind === "sheet_output") && s.name ? s.name : input ? "in" : "out");
  const color = $derived(resource ? RESOURCE_VAR[resource] : "var(--muted)");
  const title = $derived(`${input ? "Input" : "Output"} pin “${name}”: ${resource ? RESOURCE_LABEL[resource] : "carries anything"}`);
  /** Half a dot inside a sheet node's frame (its outer half is the frame's handle); a whole one on the open sheet's. */
  const half = $derived(data.pinSide === "frame");
  const knob = $derived(
    `width: ${PIN_KNOB}px; height: ${PIN_KNOB}px; top: ${PIN_DOT}px; ` +
      (input
        ? `left: 0; right: auto; transform: translate(${half ? "0" : "-50%"}, -50%); border-radius: ${half ? `0 ${PIN_KNOB}px ${PIN_KNOB}px 0` : "50%"};`
        : `left: auto; right: 0; transform: translate(${half ? "0" : "50%"}, -50%); border-radius: ${half ? `${PIN_KNOB}px 0 0 ${PIN_KNOB}px` : "50%"};`) +
      ` background: ${input ? color : "var(--panel)"}; border: 2px solid ${color}`,
  );
</script>

<div
  class="edge-pin text-xs"
  class:selected
  style="width: {PIN_W}px; height: {PIN_H}px; text-align: {input ? 'left' : 'right'}; padding-{input ? 'left' : 'right'}: {PIN_KNOB + 4}px"
  title={data.problem ? `${title}\n${data.problem}` : title}
>
  <span class="label truncate" style="color: {data.problem ? 'var(--bad)' : 'var(--ink)'}; border-bottom-color: {color}">
    {#if data.problem}<TriangleAlert size={10} class="inline" />{/if}
    {name}{#if data.signal !== undefined && resource === "redstone"}<span class="tabular-nums muted"> {data.signal}</span>{/if}
  </span>
</div>

{#if input}
  <Handle type="source" position={Position.Right} id="out" style={knob} />
{:else}
  <Handle type="target" position={Position.Left} id="in" style={knob} />
{/if}

<style>
  .edge-pin {
    position: relative;
    line-height: 14px;
  }
  .label {
    display: inline-block;
    max-width: 100%;
    padding: 0 3px;
    border-radius: 3px;
    border-bottom: 2px solid;
  }
  .selected .label {
    background: color-mix(in srgb, var(--accent) 30%, transparent);
  }
</style>
