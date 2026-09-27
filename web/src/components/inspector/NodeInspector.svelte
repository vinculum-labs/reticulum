<script lang="ts">
  import { app, edit } from "../../lib/state.svelte";
  import { KIND_HINT, KIND_LABEL, RESOURCE_UNIT } from "../../lib/kinds";
  import type { GraphNode, NodeSettings, PortFields, Sheet, SinkSettings, SourceSettings } from "../../lib/types";
  import Field from "./Field.svelte";
  import FilterField from "./FilterField.svelte";
  import PortEditor from "./PortEditor.svelte";
  import LimitsEditor from "./LimitsEditor.svelte";
  import Chart from "./Chart.svelte";

  let { node, sheet }: { node: GraphNode; sheet: Sheet } = $props();

  const canEdit = $derived(app.snapshot?.canEdit ?? false);
  const s = $derived(node.settings);
  const unit = $derived("resource" in s ? RESOURCE_UNIT[s.resource] : "units");

  function save(next: GraphNode, label = "edit node") {
    edit([{ type: "updateNode", sheet: sheet.id, node: next }], [{ type: "updateNode", sheet: sheet.id, node }], label);
  }

  function settings(patch: Partial<NodeSettings>) {
    save({ ...node, settings: { ...node.settings, ...patch } as NodeSettings });
  }

  function port(p: PortFields) {
    save({ ...node, settings: { ...node.settings, ...p } as NodeSettings });
  }

  /** Swaps a source for a sink or back, keeping the device and faces. */
  function flip() {
    const current = node.settings as SourceSettings | SinkSettings;
    const base: PortFields = { resource: current.resource, device: current.device, faces: current.faces, slots: current.slots };
    const next: NodeSettings = current.kind === "source" ? { kind: "sink", ...base } : { kind: "source", ...base, per_operation: 0 };
    const wires = (sheet.wires ?? []).filter((w) => w.from === node.id || w.to === node.id);
    if (wires.length && !confirm("Switching removes this node's wires. Continue?")) return;
    const edits = wires.map((w) => ({ type: "disconnect", sheet: sheet.id, wire: w.id }));
    const undo = [{ type: "updateNode", sheet: sheet.id, node }, ...wires.map((w) => ({ type: "connect", sheet: sheet.id, wire: w }))];
    edit([...edits, { type: "updateNode", sheet: sheet.id, node: { ...node, settings: next } }], undo, "switch source/sink");
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

  let amountMode = $derived(s.kind === "source" ? (!s.per_operation ? "stack" : s.per_operation === 1 ? "single" : "custom") : "stack");
</script>

<div class="flex flex-col gap-3">
  <div>
    <div class="flex items-center justify-between">
      <h2 class="text-base font-semibold">{KIND_LABEL[s.kind]}</h2>
      {#if canEdit}<button class="btn btn-danger text-sm" onclick={remove}>Delete</button>{/if}
    </div>
    <p class="text-xs muted">{KIND_HINT[s.kind]}</p>
  </div>

  <Field label="Label" hint={s.kind === "source" || s.kind === "sink" ? "Optional; the device's name is shown otherwise." : undefined}>
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

  {#if s.kind === "source" || s.kind === "sink"}
    <PortEditor port={s} disabled={!canEdit} onchange={port} />
    {#if canEdit}<button class="btn self-start text-sm" onclick={flip}>Make this a {s.kind === "source" ? "sink" : "source"}</button>{/if}
  {/if}

  {#if s.kind === "source"}
    {#if s.resource === "item"}
      <Field label="Per operation">
        <div class="flex items-center gap-2">
          <select
            value={amountMode}
            disabled={!canEdit}
            onchange={(e) => {
              const v = e.currentTarget.value;
              settings({ per_operation: v === "stack" ? 0 : v === "single" ? 1 : 16 });
            }}
          >
            <option value="single">One item</option>
            <option value="stack">One stack</option>
            <option value="custom">Custom</option>
          </select>
          {#if amountMode === "custom"}
            <input
              type="number"
              min="1"
              class="w-20"
              value={s.per_operation}
              disabled={!canEdit}
              onchange={(e) => settings({ per_operation: Math.max(1, Number(e.currentTarget.value) || 1) })}
            />
          {/if}
        </div>
      </Field>
      <Field label="Every" hint="Ticks between operations; 0 uses the sheet's interval ({sheet.interval ?? 10}).">
        <input
          type="number"
          min="0"
          class="w-24"
          value={s.interval ?? 0}
          disabled={!canEdit}
          onchange={(e) => settings({ interval: Math.max(0, Number(e.currentTarget.value) || 0) })}
        />
      </Field>
    {/if}
    <Field label="Priority" hint="Higher runs first when several sources feed the same sinks.">
      <input type="number" class="w-24" value={s.priority ?? 0} disabled={!canEdit} onchange={(e) => settings({ priority: Number(e.currentTarget.value) || 0 })} />
    </Field>
    <LimitsEditor limits={s.keep ?? []} keep={true} resource={s.resource} disabled={!canEdit} onchange={(keep) => settings({ keep })} />
  {:else if s.kind === "sink"}
    <LimitsEditor limits={s.caps ?? []} keep={false} resource={s.resource} disabled={!canEdit} onchange={(caps) => settings({ caps })} />
  {:else if s.kind === "filter"}
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
    <Field label="Per second">
      <input
        type="number"
        min="1"
        class="w-32"
        value={s.per_second}
        disabled={!canEdit}
        onchange={(e) => settings({ per_second: Math.max(1, Number(e.currentTarget.value) || 1) })}
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
