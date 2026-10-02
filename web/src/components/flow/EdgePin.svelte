<script lang="ts">
  import TriangleAlert from "@lucide/svelte/icons/triangle-alert";
  import { Handle, Position } from "@xyflow/svelte";
  import type { NodeData } from "../../lib/graph";
  import { PIN_DOT, PIN_H, PIN_KNOB, PIN_W } from "../../lib/graph";
  import { RESOURCE_LABEL, RESOURCE_VAR } from "../../lib/kinds";
  import { pinName } from "../../lib/types";
  import { app } from "../../lib/state.svelte";

  /**
   * A sheet's pin, anchored on its frame's edge: the dot sits on the border (inputs left, outputs right) and its name
   * outside the frame, centered on the dot. In an open sheet node the frame's own handle is the dot's outer half
   * (wires from outside), this the inner half (wires to what is inside); on the open sheet's frame, with nothing
   * outside, it is a whole dot on the line. Drag it up or down to reorder; select it to name it.
   */
  let { data, selected }: { data: NodeData & { pinSide?: "frame" | "sheet" }; selected?: boolean } = $props();

  const s = $derived(data.node.settings);
  const input = $derived(s.kind === "sheet_input");
  // Untyped: the color of what it is wired to (worked out with the sheet's resources).
  const resource = $derived((s.kind === "sheet_input" || s.kind === "sheet_output" ? s.resource : undefined) ?? data.resource ?? undefined);
  const name = $derived(pinName(data.node));
  const color = $derived(resource ? RESOURCE_VAR[resource] : "var(--muted)");
  const title = $derived(`${input ? "Input" : "Output"} pin “${name}”: ${resource ? RESOURCE_LABEL[resource] : "carries anything"}`);
  /** Half a dot inside a sheet node's frame (its outer half is the frame's handle); a whole one on the open sheet's. */
  const half = $derived(data.pinSide === "frame");
  const knob = $derived(
    `width: ${PIN_KNOB}px; height: ${PIN_KNOB}px; top: ${PIN_DOT}px; ` +
      (input
        ? `left: auto; right: 0; transform: translate(${half ? "100%" : "50%"}, -50%); border-radius: ${half ? `0 ${PIN_KNOB}px ${PIN_KNOB}px 0` : "50%"};`
        : `left: 0; right: auto; transform: translate(${half ? "-100%" : "-50%"}, -50%); border-radius: ${half ? `${PIN_KNOB}px 0 0 ${PIN_KNOB}px` : "50%"};`) +
      ` background: ${input ? color : "var(--panel)"}; border: 2px solid ${color}`,
  );
</script>

<div
  class="edge-pin text-xs"
  class:selected
  style="width: {PIN_W}px; height: {PIN_H}px; justify-content: {input ? 'flex-end' : 'flex-start'}; padding-{input ? 'right' : 'left'}: {PIN_KNOB + 4}px"
  title={data.problem ? `${title}\n${data.problem}` : title}
>
  <!-- Its color as an underline, or a tag outlined in it with its point at the dot (the side nearest the pin). -->
  <span
    class="label {app.pinStyle} {input ? 'points-right' : 'points-left'}"
    style="--pin-color: {data.problem ? 'var(--bad)' : color}; color: {data.problem ? 'var(--bad)' : 'var(--ink)'}"
  >
    <span class="text truncate">
      {#if data.problem}<TriangleAlert size={10} class="inline" />{/if}
      {name}{#if data.signal !== undefined && resource === "redstone"}<span class="tabular-nums muted"> {data.signal}</span>{/if}
    </span>
  </span>
</div>

{#if input}
  <Handle type="source" position={Position.Right} id="out" style={knob} />
{:else}
  <Handle type="target" position={Position.Left} id="in" style={knob} />
{/if}

<style>
  /* The label is centered on the dot's line: the dot sits at the pin's middle. */
  .edge-pin {
    position: relative;
    display: flex;
    align-items: center;
  }
  .label {
    display: flex;
    max-width: 100%;
    line-height: 14px;
  }
  .text {
    display: block;
    min-width: 0;
  }
  /* Underlined: an inset line, so the label keeps its height and stays centered on the dot. */
  .underline .text {
    padding: 1px 4px;
    border-radius: 3px 3px 0 0;
    box-shadow: inset 0 -2px 0 var(--pin-color);
  }
  .selected .underline .text {
    background: color-mix(in srgb, var(--accent) 30%, transparent);
  }
  /*
   * A tag: an outline in the pin's color (the outer layer, 1px larger) around a panel-colored inside, rounded at the
   * far end and cut to a point at the end nearest the dot.
   */
  .tag {
    padding: 1px;
    background: var(--pin-color);
    border-radius: 4px;
  }
  .tag .text {
    padding: 1px 4px;
    background: var(--panel);
    border-radius: 3px;
  }
  .selected .tag .text {
    background: color-mix(in srgb, var(--accent) 30%, var(--panel));
  }
  .tag.points-right,
  .tag.points-right .text {
    clip-path: polygon(0 0, calc(100% - 7px) 0, 100% 50%, calc(100% - 7px) 100%, 0 100%);
  }
  .tag.points-right .text {
    padding-right: 10px;
  }
  .tag.points-left,
  .tag.points-left .text {
    clip-path: polygon(7px 0, 100% 0, 100% 100%, 7px 100%, 0 50%);
  }
  .tag.points-left .text {
    padding-left: 10px;
  }
</style>
