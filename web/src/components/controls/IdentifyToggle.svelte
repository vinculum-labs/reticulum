<script lang="ts">
  import { app, identifyHex, isIdentified, toggleIdentify } from "../../lib/state.svelte";
  import { posKey, type Identify } from "../../lib/types";

  /**
   * The ID button: lights a connector, device or face up in the world, or turns it off. While on it shows the
   * identify's color, as the card's badge and the world do.
   */
  let { target, title = "Light it up in the world until turned off" }: { target: Identify; title?: string } = $props();

  const canEdit = $derived(app.snapshot?.canEdit ?? false);
  const on = $derived(isIdentified(target));
  const color = $derived(
    identifyHex(
      (app.snapshot?.identified ?? []).find(
        (x) =>
          x.kind === target.kind &&
          (x.face ?? "") === (target.face ?? "") &&
          (x.device && target.device ? posKey(x.device) === posKey(target.device) : (x.connector ?? "") === (target.connector ?? "")),
      )?.color,
    ),
  );
</script>

<button
  type="button"
  class="btn shrink-0 text-sm"
  aria-pressed={on}
  style={on ? `border-color: ${color}; color: ${color}` : ""}
  disabled={!canEdit}
  {title}
  onclick={() => toggleIdentify(target)}>ID</button
>
