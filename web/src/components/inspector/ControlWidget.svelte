<script lang="ts">
  import { app } from "../../lib/state.svelte";
  import type { Control } from "../../lib/dashboard";
  import SwitchControl from "./SwitchControl.svelte";
  import { iconUrl } from "../../lib/catalog";
  import { KIND_ICON } from "../../lib/icons";

  /**
   * A Switch or Display as the dashboard shows it, where it runs: a switch to flip (or a slider for a level switch), or the
   * value wired into a display — a number, a gauge or a light.
   */
  let { control, label, canEdit }: { control: Pick<Control, "node" | "sheet" | "run">; label: string; canEdit: boolean } = $props();

  const s = $derived(control.node.settings);
  const value = $derived(app.signals[control.run] ?? 0);

  function format(v: number): string {
    return Math.abs(v) >= 10_000 ? v.toLocaleString() : String(v);
  }
</script>

<!-- Its icon (an item chosen for it, else its kind's) and name. -->
{#snippet name()}
  <span class="flex min-w-0 flex-1 items-center gap-2">
    {#if s.kind === "switch" || s.kind === "display"}
      {#if s.icon}
        <img src={iconUrl("item", s.icon)} alt="" width="24" height="24" class="shrink-0" style="image-rendering: pixelated" />
      {:else}
        {@const Icon = KIND_ICON[s.kind]}
        <span class="flex h-6 w-6 shrink-0 items-center justify-center muted"><Icon size={16} /></span>
      {/if}
    {/if}
    <span class="flex min-w-0 flex-col">
      <span class="trim-text truncate">{label}</span>
      {#if control.node.note}<span class="note truncate text-xs muted" title={control.node.note}>{control.node.note}</span>{/if}
    </span>
  </span>
{/snippet}

{#if s.kind === "switch"}
  <div class="flex items-center gap-2">
    {@render name()}
    <SwitchControl {control} {label} {canEdit} />
  </div>
{:else if s.kind === "display"}
  {#if s.style === "light"}
    <div class="flex items-center gap-2">
      {@render name()}
      <span class="light" class:lit={value > 0} style={s.color ? `--lit: ${s.color}` : ""} title={value > 0 ? `On (${value})` : "Off"}></span>
    </div>
  {:else if s.style === "message"}
    <!-- The last message whose start the value has reached; below the first, the number. -->
    {@const messages = [...(s.messages ?? [])].sort((a, b) => (a.from ?? 0) - (b.from ?? 0))}
    {@const shown = messages.filter((m) => value >= (m.from ?? 0)).at(-1)}
    <!-- Room for numbers as long as the widest value it has shown or any message starts at (and two digits at least). -->
    {@const digits = Math.max(2, format(value).length, ...messages.map((m) => format(m.from ?? 0).length + 1))}
    {@const widest = "8".repeat(digits)}
    <!-- The message beside the name: the dashboard's card grows to fit it; where there is no room (the inspector, a card
         at its widest) it goes onto a line of its own below. Every message (and the number) is laid out in the same
         place, only the one shown visible, so the space fits the longest and nothing moves as the value changes. -->
    <div class="message-row flex flex-wrap items-center gap-x-2 gap-y-0.5">
      {@render name()}
      <span class="message ml-auto font-semibold" title={shown ? `${(shown.text ?? "").replaceAll("{value}", format(value))} (${value})` : String(value)}>
        {#each messages as m, i (i)}
          {@const text = (m.text ?? "").replaceAll("{value}", format(value))}
          <span class:hidden-message={m !== shown} style={m.color ? `color: ${m.color}` : ""} aria-hidden={m !== shown}>{text || "—"}</span>
        {/each}
        <span class="tabular-nums" class:hidden-message={!!shown} aria-hidden={!!shown}>{format(value)}</span>
        <!-- Sizing only: each message with its number as wide as it may get. -->
        {#each messages as m, i (i)}
          {#if m.text?.includes("{value}")}<span class="hidden-message" aria-hidden="true">{m.text.replaceAll("{value}", widest)}</span>{/if}
        {/each}
      </span>
    </div>
  {:else if s.style === "gauge"}
    {@const max = s.max ?? 15}
    <div class="flex flex-col gap-1">
      <div class="flex items-center gap-2">
        {@render name()}
        <span class="tabular-nums">{format(value)}{s.unit ? ` ${s.unit}` : ""} <span class="muted">/ {format(max)}</span></span>
      </div>
      <div class="gauge" role="meter" aria-label={label} aria-valuemin={0} aria-valuemax={max} aria-valuenow={value}>
        <div style="width: {Math.max(0, Math.min(100, (value / max) * 100))}%{s.color ? `; background: ${s.color}` : ''}"></div>
      </div>
    </div>
  {:else}
    <div class="flex items-center gap-2">
      {@render name()}
      <span class="text-base font-semibold tabular-nums" style={s.color ? `color: ${s.color}` : ""}>{format(value)}</span>{#if s.unit}<span class="muted">{s.unit}</span>{/if}
    </div>
  {/if}
{/if}

<style>
  /* A note widens the dashboard's card for itself only so far: a longer one is cut short with "…" (its tooltip has it all). */
  .note {
    max-width: 20rem;
  }
  /* The name keeps its width; the message goes below rather than squeezing it. */
  .message-row > :global(:first-child) {
    flex: 1 0 auto;
    max-width: 100%;
  }
  .message {
    display: grid;
    font-variant-numeric: tabular-nums;
    justify-items: end;
    margin-left: auto;
    max-width: 100%;
    text-align: right;
    overflow-wrap: anywhere;
  }
  .message > span {
    grid-area: 1 / 1;
  }
  .hidden-message {
    visibility: hidden;
  }
  .light {
    width: 12px;
    height: 12px;
    border-radius: 50%;
    flex-shrink: 0;
    background: var(--panel-2);
    border: 1px solid var(--line);
  }
  .light.lit {
    background: var(--lit, var(--good));
    border-color: var(--lit, var(--good));
    box-shadow: 0 0 6px var(--lit, var(--good));
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
