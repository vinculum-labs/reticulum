<script lang="ts">
  import { getBezierPath, getSmoothStepPath, getStraightPath, Position, useConnection } from "@xyflow/svelte";
  import { app } from "../../lib/state.svelte";
  import { pinDrop } from "../../lib/pinDrop.svelte";

  /**
   * The wire being dragged, in the wire style, from its pin to the pointer; or, past a frame's edge where letting go
   * makes a pin, only as far as that pin's dot on the edge.
   */
  const connection = useConnection();

  const path = $derived.by(() => {
    const c = connection.current;
    if (!c.inProgress) return "";
    const at = pinDrop.at;
    const params = {
      sourceX: c.from.x,
      sourceY: c.from.y,
      sourcePosition: c.fromPosition,
      targetX: at ? at.x : c.to.x,
      targetY: at ? at.y : c.to.y,
      // Into the dot from inside the frame: an output pin's from the left, an input pin's from the right.
      targetPosition: at ? (at.output ? Position.Left : Position.Right) : c.toPosition,
    };
    switch (app.wireStyle) {
      case "straight":
        return getStraightPath(params)[0];
      case "step":
      case "smoothstep":
        return getSmoothStepPath({ ...params, borderRadius: app.wireStyle === "step" ? 0 : undefined })[0];
      default:
        return getBezierPath(params)[0];
    }
  });
</script>

<path d={path} fill="none" class="svelte-flow__connection-path" />
