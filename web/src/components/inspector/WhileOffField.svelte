<script lang="ts">
  import type { OffBehavior } from "../../lib/types";
  import NumberField from "../controls/NumberField.svelte";
  import Segmented from "../controls/Segmented.svelte";
  import Field from "./Field.svelte";

  /**
   * What a signal output does while its sheet is switched off: holds its last value, goes to a set value, or keeps
   * the logic feeding it running.
   */
  let { value, disabled = false, onchange }: { value: OffBehavior | undefined; disabled?: boolean; onchange: (value: OffBehavior) => void } = $props();

  const mode = $derived(value?.mode ?? "hold");
</script>

<Field label="While its sheet is off" hint="Hold keeps the last value. Fixed gives a set value. Keep running works out what feeds it as usual.">
  <div class="flex items-center gap-2">
    <Segmented
      label="While its sheet is off"
      size="xs"
      value={mode}
      {disabled}
      options={[
        { value: "hold", label: "Hold" },
        { value: "fixed", label: "Fixed" },
        { value: "run", label: "Keep running" },
      ]}
      onchange={(m) => onchange(m === "fixed" ? { mode: m, value: value?.value ?? 0 } : { mode: m })}
    />
    {#if mode === "fixed"}
      <NumberField value={value?.value ?? 0} min={0} max={15} width="sm" {disabled} title="The value it gives while off, 0 to 15"
        oncommit={(v) => onchange({ mode: "fixed", value: v })} />
    {/if}
  </div>
</Field>
