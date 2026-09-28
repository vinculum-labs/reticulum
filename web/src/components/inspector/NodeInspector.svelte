<script lang="ts">
  import { app, edit } from "../../lib/state.svelte";
  import { KIND_HINT, KIND_LABEL, RESOURCE_UNIT, STATUS_LABEL, formatAgo, formatAverage, formatRate } from "../../lib/kinds";
  import { pinName, sheetPins, type DeviceNodeSettings, type Edit, type GraphNode, type NodeSettings, type Sheet, type Wire } from "../../lib/types";
  import Field from "./Field.svelte";
  import FilterField from "./FilterField.svelte";
  import DeviceNodeEditor from "./DeviceNodeEditor.svelte";
  import Chart from "./Chart.svelte";

  let { node, sheet }: { node: GraphNode; sheet: Sheet } = $props();

  const canEdit = $derived(app.snapshot?.canEdit ?? false);
  const s = $derived(node.settings);
  const unit = $derived(s.kind === "device" && s.outputs?.[0] ? RESOURCE_UNIT[s.outputs[0].resource] : "units");

  /**
   * Saves a device node. When an input or output was removed, its wires go and wires on later ones are
   * renumbered (the server identifies them by number).
   */
  function saveDevice(next: DeviceNodeSettings, removed?: { direction: "in" | "out"; index: number }) {
    const edits: Edit[] = [];
    const undo: Edit[] = [{ type: "updateNode", sheet: sheet.id, node }];
    if (removed) {
      const onSide = (w: Wire) => (removed.direction === "in" ? w.to === node.id : w.from === node.id);
      const index = (w: Wire) => (removed.direction === "in" ? (w.input ?? 0) : (w.output ?? 0));
      for (const w of (sheet.wires ?? []).filter((w) => onSide(w) && index(w) >= removed.index)) {
        edits.push({ type: "disconnect", sheet: sheet.id, wire: w.id });
        undo.push({ type: "connect", sheet: sheet.id, wire: w });
        if (index(w) > removed.index) {
          const moved = removed.direction === "in" ? { ...w, input: index(w) - 1 } : { ...w, output: index(w) - 1 };
          edits.push({ type: "connect", sheet: sheet.id, wire: moved });
          undo.unshift({ type: "disconnect", sheet: sheet.id, wire: w.id });
        }
      }
    }
    edit([...edits, { type: "updateNode", sheet: sheet.id, node: { ...node, settings: next } }], undo, "edit device");
  }

  function save(next: GraphNode, label = "edit node") {
    edit([{ type: "updateNode", sheet: sheet.id, node: next }], [{ type: "updateNode", sheet: sheet.id, node }], label);
  }

  function settings(patch: Partial<NodeSettings>) {
    save({ ...node, settings: { ...node.settings, ...patch } as NodeSettings });
  }

  function remove() {
    const wires = (sheet.wires ?? []).filter((w) => w.from === node.id || w.to === node.id);
    app.selectedNodes = [];
    edit(
      [{ type: "removeNode", sheet: sheet.id, node: node.id }],
      [{ type: "addNode", sheet: sheet.id, node }, ...wires.map((w) => ({ type: "connect", sheet: sheet.id, wire: w }))],
      "delete node",
    );
  }

</script>

<div class="flex flex-col gap-3">
  <div>
    <div class="flex items-center justify-between">
      <h2 class="text-base font-semibold">{KIND_LABEL[s.kind]}</h2>
      {#if canEdit}<button class="btn btn-danger text-sm" onclick={remove}>Delete</button>{/if}
    </div>
    <p class="text-xs muted">{KIND_HINT[s.kind]}</p>
  </div>

  <div class="rounded-md border p-2 text-sm" style="border-color: var(--line)">
    {#if (app.rates[node.id] ?? 0) > 0}
      Moving {formatRate(app.rates[node.id], null, app.rateUnit)}
    {:else if app.status[node.id]}
      <span style="color: var(--warn)">Idle: {STATUS_LABEL[app.status[node.id]] ?? app.status[node.id]}</span>
    {:else}
      Idle
    {/if}
    <div class="text-xs muted">
      Average {formatAverage(app.fiveMinutes[node.id] ?? 0, null, app.rateUnit)} over 5 min ·
      {app.ticksSinceMoved[node.id] !== undefined ? `last moved ${formatAgo(app.ticksSinceMoved[node.id])}` : "never moved"}
    </div>
  </div>

  <Field label="Label" hint={s.kind === "device" ? "Optional; the device's name is shown otherwise." : undefined}>
    <input
      value={node.label ?? ""}
      disabled={!canEdit}
      placeholder="Optional"
      onchange={(e) => save({ ...node, label: e.currentTarget.value.trim() || undefined }, "rename node")}
    />
  </Field>

  <label class="flex items-center gap-2 text-sm">
    <input type="checkbox" checked={node.enabled !== false} disabled={!canEdit} onchange={(e) => save({ ...node, enabled: e.currentTarget.checked }, "node on/off")} />
    On
  </label>

  {#if s.kind === "device"}
    <DeviceNodeEditor settings={s} {sheet} nodeId={node.id} disabled={!canEdit} onchange={saveDevice} />
  {/if}

  {#if s.kind === "subsheet"}
    {@const choices = (app.snapshot?.sheets ?? []).filter((x) => x.subsheet && x.id !== sheet.id)}
    {@const used = app.snapshot?.sheets.find((x) => x.id === s.sheet)}
    <Field label="Sub-sheet" hint="Only sheets marked “Use as sub-sheet” are listed. Double-click the node to open it.">
      <select value={s.sheet} disabled={!canEdit} onchange={(e) => settings({ sheet: e.currentTarget.value })}>
        {#if !used}<option value={s.sheet}>(missing)</option>{/if}
        {#each choices as choice (choice.id)}<option value={choice.id}>{choice.name}</option>{/each}
      </select>
    </Field>
    {#if used}
      <div class="text-sm">
        <div><span class="muted">Inputs:</span> {sheetPins(used, true).map(pinName).join(", ") || "none"}</div>
        <div><span class="muted">Outputs:</span> {sheetPins(used, false).map(pinName).join(", ") || "none"}</div>
      </div>
      <button class="btn self-start text-sm" onclick={() => (app.sheetId = used.id)}>Open “{used.name}”</button>
    {/if}
  {:else if s.kind === "sheet_input" || s.kind === "sheet_output"}
    <Field
      label="Pin name"
      hint={`Shown on the ${s.kind === "sheet_input" ? "left" : "right"} of sub-sheet nodes that use this sheet. Pins are ordered top to bottom as placed here.`}
    >
      <input value={s.name ?? ""} disabled={!canEdit} placeholder="e.g. ores" onchange={(e) => settings({ name: e.currentTarget.value.trim() })} />
    </Field>
  {/if}

  {#if s.kind === "filter"}
    <Field label="Filter" hint="Matches go out the green handle, everything else out the red one.">
      <FilterField value={s.expression} disabled={!canEdit} onchange={(expression) => settings({ expression })} />
    </Field>
    <details class="text-xs muted">
      <summary class="cursor-pointer">Filter syntax</summary>
      <ul class="mt-1 list-disc pl-4">
        <li><code>minecraft:*_ore</code>, <code>iron_ingot</code> ids with <code>*</code></li>
        <li><code>#c:ores</code> or <code>tag:c:ores</code>, <code>mod:mekanism</code></li>
        <li><code>name:"iron"</code>, <code>name:/^Raw/</code></li>
        <li><code>dura:&lt;25%</code>, <code>ench:minecraft:fortune&gt;=3</code>, <code>ench:any</code></li>
        <li><code>is:fuel|food|stackable|damaged|enchanted|smeltable</code>, <code>has:minecraft:custom_name</code></li>
        <li><code>in:sink</code> only what the sink already holds; <code>@name</code> a template</li>
        <li>Fluids: <code>temp:&gt;1000</code>, <code>is:gaseous</code></li>
        <li>Combine with <code>&amp;</code> (or just a space), <code>|</code>, <code>!</code> and brackets</li>
      </ul>
    </details>
  {:else if s.kind === "rate_limit"}
    <Field label={app.rateUnit === "t" ? "Per tick" : "Per second"} hint={`${unit}; the top bar switches between per tick and per second.`}>
      <input
        type="number"
        min={app.rateUnit === "t" ? 0.05 : 1}
        step={app.rateUnit === "t" ? 0.05 : 1}
        class="w-32"
        value={app.rateUnit === "t" ? Number((s.per_second / 20).toFixed(2)) : s.per_second}
        disabled={!canEdit}
        onchange={(e) => {
          const entered = Number(e.currentTarget.value) || 0;
          settings({ per_second: Math.max(1, Math.round(app.rateUnit === "t" ? entered * 20 : entered)) });
        }}
      />
    </Field>
  {:else if s.kind === "splitter"}
    <Field label="Mode" hint="Round robin shares out (evenly for fluids and energy); in order fills the first output before the next.">
      <select value={s.mode ?? "round_robin"} disabled={!canEdit} onchange={(e) => settings({ mode: e.currentTarget.value as "round_robin" | "priority" })}>
        <option value="round_robin">Round robin</option>
        <option value="priority">In order (wire order)</option>
      </select>
    </Field>
  {:else if s.kind === "kit"}
    <div class="flex flex-col gap-2">
      <span class="text-xs font-medium muted">Items in one kit (all move together, or none)</span>
      {#each s.lines as line, i}
        <div class="flex items-start gap-1">
          <input
            type="number"
            min="1"
            class="w-16"
            value={line.count}
            disabled={!canEdit}
            onchange={(e) => settings({ lines: s.lines.map((l, j) => (j === i ? { ...l, count: Math.max(1, Number(e.currentTarget.value) || 1) } : l)) })}
          />
          <span class="pt-1 muted">×</span>
          <div class="min-w-0 flex-1">
            <FilterField value={line.filter} disabled={!canEdit} placeholder="e.g. iron_ingot" onchange={(v) => settings({ lines: s.lines.map((l, j) => (j === i ? { ...l, filter: v } : l)) })} />
          </div>
          <button class="pt-1 text-sm muted" disabled={!canEdit || s.lines.length === 1} onclick={() => settings({ lines: s.lines.filter((_, j) => j !== i) })}>✕</button>
        </div>
      {/each}
      <button class="btn self-start text-sm" disabled={!canEdit} onclick={() => settings({ lines: [...s.lines, { filter: "", count: 1 }] })}>+ Item</button>
      <Field label="Kits per operation">
        <input type="number" min="1" class="w-20" value={s.max_kits ?? 1} disabled={!canEdit} onchange={(e) => settings({ max_kits: Math.max(1, Number(e.currentTarget.value) || 1) })} />
      </Field>
    </div>
  {/if}

  <Chart item={node.id} {unit} />
</div>
