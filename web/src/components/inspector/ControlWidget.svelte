<script lang="ts">
  import { app, toast } from "../../lib/state.svelte";
  import { setSwitch, type Control } from "../../lib/dashboard";
  import Switch from "../controls/Switch.svelte";

  /**
   * A Switch or Display as the dashboard shows it, where it runs: a switch to flip (or a slider for a level switch), or the
   * value wired into a display — a number, a gauge or a light.
   */
  let { control, label, canEdit }: { control: Pick<Control, "node" | "sheet" | "run">; label: string; canEdit: boolean } = $props();

  const s = $derived(control.node.settings);
  const value = $derived(app.signals[control.run] ?? 0);
  /** A level switch's slider, while it is dragged (sent when let go). */
  let dragging = $state<number | null>(null);

  function set(to: number) {
    setSwitch(control, to).catch((e) => toast(e instanceof Error ? e.message : String(e)));
  }

  function format(v: number): string {
    return Math.abs(v) >= 10_000 ? v.toLocaleString() : String(v);
  }
</script>

{#if s.kind === "switch"}
  {@const max = s.value ?? 15}
  {#if s.level}
    <div class="flex items-center gap-2">
      <span class="min-w-0 flex-1 truncate">{label}</span>
      <input
        type="range"
        class="w-28"
        min="0"
        {max}
        value={dragging ?? value}
        disabled={!canEdit}
        aria-label={label}
        oninput={(e) => (dragging = Number(e.currentTarget.value))}
        onchange={(e) => {
          set(Number(e.currentTarget.value));
          dragging = null;
        }}
      />
      <span class="w-8 text-right tabular-nums">{dragging ?? value}</span>
    </div>
  {:else}
    <div class="flex items-center gap-2">
      <span class="min-w-0 flex-1 truncate">{label}</span>
      <Switch
        label={label}
        hideLabel
        checked={value > 0}
        disabled={!canEdit}
        title={canEdit ? `${value > 0 ? "On" : "Off"} — click to turn it ${value > 0 ? "off" : "on"}` : "Only players who can edit the network flip switches"}
        onchange={(on) => set(on ? max : 0)}
      />
    </div>
  {/if}
{:else if s.kind === "display"}
  {#if s.style === "light"}
    <div class="flex items-center gap-2">
      <span class="min-w-0 flex-1 truncate">{label}</span>
      <span class="light" class:lit={value > 0} title={value > 0 ? `On (${value})` : "Off"}></span>
    </div>
  {:else if s.style === "gauge"}
    {@const max = s.max ?? 15}
    <div class="flex flex-col gap-1">
      <div class="flex items-center gap-2">
        <span class="min-w-0 flex-1 truncate">{label}</span>
        <span class="tabular-nums">{format(value)}{s.unit ? ` ${s.unit}` : ""} <span class="muted">/ {format(max)}</span></span>
      </div>
      <div class="gauge" role="meter" aria-label={label} aria-valuemin={0} aria-valuemax={max} aria-valuenow={value}>
        <div style="width: {Math.max(0, Math.min(100, (value / max) * 100))}%"></div>
      </div>
    </div>
  {:else}
    <div class="flex items-center gap-2">
      <span class="min-w-0 flex-1 truncate">{label}</span>
      <span class="text-base font-semibold tabular-nums">{format(value)}</span>{#if s.unit}<span class="muted">{s.unit}</span>{/if}
    </div>
  {/if}
{/if}

<style>
  .light {
    width: 12px;
    height: 12px;
    border-radius: 50%;
    flex-shrink: 0;
    background: var(--panel-2);
    border: 1px solid var(--line);
  }
  .light.lit {
    background: var(--good);
    border-color: var(--good);
    box-shadow: 0 0 6px var(--good);
  }
  .gauge {
    height: 8px;
    border-radius: 4px;
    background: var(--panel-2);
    border: 1px solid var(--line);
    overflow: hidden;
  }
  .gauge > div {
    height: 100%;
    background: var(--accent);
    transition: width 0.3s;
  }
</style>
