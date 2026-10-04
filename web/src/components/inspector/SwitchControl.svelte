<script lang="ts">
  import { app, toast } from "../../lib/state.svelte";
  import { setSwitch, type Control } from "../../lib/dashboard";
  import Switch from "../controls/Switch.svelte";

  /**
   * A Switch node's control where it runs: an on/off switch, or for a level switch a slider and its value. On the
   * dashboard and on its card. {@code why} says why it can't be used (disabled) when that isn't only edit access.
   */
  let {
    control,
    label,
    canEdit,
    why,
    sliderClass = "w-28",
  }: { control: Pick<Control, "node" | "sheet" | "run">; label: string; canEdit: boolean; why?: string; sliderClass?: string } = $props();

  const s = $derived(control.node.settings);
  const value = $derived(app.signals[control.run] ?? 0);
  const usable = $derived(canEdit && !why);
  const tip = $derived(why ?? (canEdit ? undefined : "Only players who can edit the network flip switches"));
  /**
   * A level switch's slider while held (from the press, so values coming in meanwhile don't move it under the pointer):
   * sent when let go.
   */
  let dragging = $state<number | null>(null);

  function set(to: number) {
    setSwitch(control, to).catch((e) => toast(e instanceof Error ? e.message : String(e)));
  }
</script>

{#if s.kind === "switch"}
  {@const max = s.value ?? 15}
  {#if s.level}
    <span class="flex shrink-0 items-center gap-2" title={tip}>
      <input
        type="range"
        class={sliderClass}
        min="0"
        {max}
        value={dragging ?? value}
        style="--fill: {max > 0 ? (Math.max(0, Math.min(max, dragging ?? value)) / max) * 100 : 0}%"
        disabled={!usable}
        aria-label={label}
        onpointerdown={() => (dragging = value)}
        onpointerup={(e) => Number(e.currentTarget.value) === value && (dragging = null)}
        onpointercancel={() => (dragging = null)}
        oninput={(e) => (dragging = Number(e.currentTarget.value))}
        onchange={(e) => {
          set(Number(e.currentTarget.value));
          dragging = null;
        }}
      />
      <span class="w-8 text-right tabular-nums">{dragging ?? value}</span>
    </span>
  {:else}
    <Switch
      {label}
      hideLabel
      checked={value > 0}
      disabled={!usable}
      title={tip ?? `${value > 0 ? "On" : "Off"} — click to turn it ${value > 0 ? "off" : "on"}`}
      onchange={(on) => set(on ? max : 0)}
    />
  {/if}
{/if}
