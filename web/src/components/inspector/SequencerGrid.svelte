<script lang="ts">
  import Trash from "@lucide/svelte/icons/trash";
  import type { SequencerTrack } from "../../lib/types";
  import NumberField from "../controls/NumberField.svelte";
  import TextField from "../controls/TextField.svelte";

  /**
   * A Sequencer's tracks as a grid: a row per track, a cell per step. Click a cell to set it to the brush value (or
   * clear it, if it already has that value); drag to paint along a row. The step playing is highlighted.
   */
  let {
    tracks,
    steps,
    playing,
    canEdit,
    onchange,
    onremove,
  }: {
    tracks: SequencerTrack[];
    steps: number;
    /** The step playing, 1 on (0: stopped). */
    playing: number;
    canEdit: boolean;
    onchange: (tracks: SequencerTrack[]) => void;
    onremove: (index: number) => void;
  } = $props();

  let brush = $state(15);
  /** Cells being painted, shown at once and saved as one change when the button comes up. */
  let painting = $state<string[] | null>(null);
  let paintValue = 0;
  const shown = $derived(painting ?? tracks.map((t) => t.cells ?? ""));

  function cell(cells: string, step: number): number {
    const value = step < cells.length ? parseInt(cells[step], 16) : 0;
    return Number.isNaN(value) ? 0 : value;
  }

  function withCell(cells: string, step: number, value: number): string {
    const padded = cells.padEnd(step + 1, "0");
    return padded.slice(0, step) + value.toString(16) + padded.slice(step + 1);
  }

  function start(track: number, step: number, event: PointerEvent) {
    if (!canEdit || event.button !== 0) return;
    painting = tracks.map((t) => t.cells ?? "");
    // The first cell decides: setting the brush value, or clearing (when it already had it).
    paintValue = cell(painting[track], step) === brush ? 0 : brush;
    paint(track, step);
  }

  function paint(track: number, step: number) {
    if (!painting) return;
    painting = painting.map((cells, i) => (i === track ? withCell(cells, step, paintValue) : cells));
  }

  function finish() {
    if (!painting) return;
    const next = tracks.map((t, i) => ({ ...t, cells: painting![i].replace(/0+$/, "") }));
    painting = null;
    onchange(next);
  }
</script>

<svelte:window onpointerup={finish} />

<div class="flex flex-col gap-2">
  <div class="flex items-center gap-2 text-xs">
    <span class="muted">Brush</span>
    <NumberField value={brush} min={1} max={15} width="sm" disabled={!canEdit} oncommit={(v) => (brush = Math.max(1, Math.min(15, v ?? 15)))} />
    <span class="muted">— click a cell to set it (or clear it), drag to paint along a row</span>
  </div>
  <div class="grid-scroll overflow-x-auto pb-1">
    <table class="seq">
      <thead>
        <tr>
          <th></th>
          {#each { length: steps } as _, step}
            <th class:playing={playing === step + 1}>{step % 4 === 0 ? step + 1 : ""}</th>
          {/each}
          <th></th>
        </tr>
      </thead>
      <tbody>
        {#each tracks as track, i}
          <tr>
            <td class="name">
              <TextField
                value={track.name ?? ""}
                placeholder={`Track ${i + 1}`}
                disabled={!canEdit}
                oncommit={(name) => onchange(tracks.map((t, j) => (j === i ? { ...t, name: name.slice(0, 32) } : t)))}
              />
            </td>
            {#each { length: steps } as _, step}
              {@const value = cell(shown[i] ?? "", step)}
              <td
                class="cell"
                class:playing={playing === step + 1}
                class:beat={step % 4 === 0}
                style={value ? `background: color-mix(in srgb, var(--c-signal) ${30 + value * 70 / 15}%, transparent)` : ""}
                title={`Track ${i + 1}, step ${step + 1}: ${value || "nothing"}`}
                onpointerdown={(e) => start(i, step, e)}
                onpointerenter={() => paint(i, step)}
              >{value && value !== 15 ? value.toString(16).toUpperCase() : ""}</td>
            {/each}
            <td>
              {#if canEdit}
                <button class="btn px-1" title="Remove this track (its wire goes)" aria-label={`Remove track ${i + 1}`} onclick={() => onremove(i)}><Trash size={12} /></button>
              {/if}
            </td>
          </tr>
        {/each}
      </tbody>
    </table>
  </div>
</div>

<style>
  .seq {
    border-collapse: separate;
    border-spacing: 2px;
    user-select: none;
  }
  .seq th {
    font-size: 10px;
    font-weight: normal;
    color: var(--muted);
    min-width: 14px;
    text-align: left;
  }
  .seq th.playing {
    color: var(--accent);
  }
  .name {
    min-width: 90px;
    max-width: 90px;
  }
  .cell {
    width: 14px;
    height: 14px;
    min-width: 14px;
    border-radius: 2px;
    background: var(--panel-2);
    font-size: 9px;
    line-height: 14px;
    text-align: center;
    cursor: pointer;
  }
  .cell.beat {
    box-shadow: inset 1px 0 0 var(--line);
  }
  .cell.playing {
    outline: 1px solid var(--accent);
  }
</style>
