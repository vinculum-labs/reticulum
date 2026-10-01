<script lang="ts">
  import { app, deviceAt, deviceName, edit, nodeTitle, openDevice, openSheet, rebase, signalNames } from "../../lib/state.svelte";
  import { KIND_HINT, KIND_LABEL, OP_SYMBOL, meUsageLabel, moves, RESOURCE_LABEL, RESOURCE_UNIT, isSignal } from "../../lib/kinds";
  import {
    SIDES,
    posKey,
    pinName,
    sheetPins,
    type CompareOp,
    type DeviceNodeSettings,
    type Edit,
    type Face,
    type GraphNode,
    type LogicOp,
    type SplitMode,
    type NodeSettings,
    type Resource,
    type Sheet,
    type Wire,
  } from "../../lib/types";
  import DevicePicker from "./DevicePicker.svelte";
  import RedstoneEditor from "./RedstoneEditor.svelte";
  import Field from "./Field.svelte";
  import FilterField from "./FilterField.svelte";
  import { catalogKind } from "../../lib/catalog";
  import { resources, splitterBranches, upstreamDevices } from "../../lib/graph";
  import { useSvelteFlow } from "@xyflow/svelte";
  import ArrowDown from "@lucide/svelte/icons/arrow-down";
  import ArrowUp from "@lucide/svelte/icons/arrow-up";
  import DeviceNodeEditor from "./DeviceNodeEditor.svelte";
  import Chart from "./Chart.svelte";
  import InspectorPanel from "./InspectorPanel.svelte";
  import ThroughputSummary from "./ThroughputSummary.svelte";
  import IdentifyToggle from "../controls/IdentifyToggle.svelte";
  import NumberField from "../controls/NumberField.svelte";
  import Switch from "../controls/Switch.svelte";
  import RemoveButton from "../controls/RemoveButton.svelte";
  import TextField from "../controls/TextField.svelte";

  let { node, sheet }: { node: GraphNode; sheet: Sheet } = $props();

  const canEdit = $derived(app.snapshot?.canEdit ?? false);
  const s = $derived(node.settings);
  const signal = $derived(isSignal(s.kind));
  /** The device the node points at, whose names (and face names) can be edited. */
  const namedDevice = $derived(
    "device" in s ? deviceAt(posKey(s.device)) : undefined,
  );
  const devices = $derived(app.snapshot?.devices ?? []);
  const names = $derived(signalNames());
  const unit = $derived(s.kind === "device" && s.outputs?.[0] ? RESOURCE_UNIT[s.outputs[0].resource] : "units");

  /**
   * Saves a device node. Removed inputs and outputs (by their old index) lose their wires, and wires on the ones
   * after them are renumbered (the server identifies them by number).
   */
  function saveDevice(next: DeviceNodeSettings, removed?: { in?: number[]; out?: number[] }, label = "edit device") {
    const edits: Edit[] = [];
    const { after, before } = rebase(node, { ...node, settings: next });
    const undo: Edit[] = [{ type: "updateNode", sheet: sheet.id, node: before }];
    for (const direction of ["in", "out"] as const) {
      const gone = new Set(removed?.[direction] ?? []);
      if (!gone.size) continue;
      const onSide = (w: Wire) => (direction === "in" ? w.to === node.id : w.from === node.id);
      const index = (w: Wire) => (direction === "in" ? (w.input ?? 0) : (w.output ?? 0));
      const shift = (i: number) => [...gone].filter((g) => g < i).length;
      for (const w of (sheet.wires ?? []).filter((w) => onSide(w) && (gone.has(index(w)) || shift(index(w)) > 0))) {
        edits.push({ type: "disconnect", sheet: sheet.id, wire: w.id });
        undo.push({ type: "connect", sheet: sheet.id, wire: w });
        if (!gone.has(index(w))) {
          const to = index(w) - shift(index(w));
          const moved = direction === "in" ? { ...w, input: to } : { ...w, output: to };
          edits.push({ type: "connect", sheet: sheet.id, wire: moved });
          undo.unshift({ type: "disconnect", sheet: sheet.id, wire: w.id });
        }
      }
    }
    edit([...edits, { type: "updateNode", sheet: sheet.id, node: after }], undo, label);
  }

  function save(next: GraphNode, label = "edit node") {
    const { after, before } = rebase(node, next);
    edit([{ type: "updateNode", sheet: sheet.id, node: after }], [{ type: "updateNode", sheet: sheet.id, node: before }], label);
  }

  function settings(patch: Partial<NodeSettings>) {
    save({ ...node, settings: { ...node.settings, ...patch } as NodeSettings });
  }

  function splitterOutputs(): Wire[] {
    return splitterBranches(sheet, node.id);
  }

  /** Swaps a branch with its neighbor; a weighted splitter's weights move with their branches. */
  function moveOutput(index: number, step: number) {
    const outs = splitterOutputs();
    const other = index + step;
    if (other < 0 || other >= outs.length || node.settings.kind !== "splitter") return;
    const ids = outs.map((w) => w.id);
    [ids[index], ids[other]] = [ids[other], ids[index]];
    const edits: Edit[] = [{ type: "orderWires", sheet: sheet.id, wires: ids }];
    const undo: Edit[] = [{ type: "orderWires", sheet: sheet.id, wires: outs.map((w) => w.id) }];
    const weights = node.settings.weights;
    if (weights?.length) {
      const next = outs.map((_, j) => weights[j] ?? 1);
      [next[index], next[other]] = [next[other], next[index]];
      const { after, before } = rebase(node, { ...node, settings: { ...node.settings, weights: next } });
      edits.push({ type: "updateNode", sheet: sheet.id, node: after });
      undo.push({ type: "updateNode", sheet: sheet.id, node: before });
    }
    edit(edits, undo, "reorder outputs");
  }

  const flow = useSvelteFlow();

  /** Deletes the node the way the Delete key does, with its wires, as one undoable change. */
  function remove() {
    flow.deleteElements({ nodes: [{ id: node.id }] });
  }

</script>

<InspectorPanel title={KIND_LABEL[s.kind]} subtitle={KIND_HINT[s.kind]}>
  {#snippet actions()}
    {#if canEdit}<button class="btn btn-danger text-sm" onclick={remove}>Delete</button>{/if}
  {/snippet}

  <ThroughputSummary id={node.id} {signal} />

  {#if namedDevice}
    <div class="flex min-w-0 gap-2">
      <!-- A long device name is cut short here rather than pushing the ID button out of the panel. -->
      <button class="btn min-w-0 flex-1 truncate text-left text-sm" onclick={() => openDevice(namedDevice)} title={`Rename “${deviceName(namedDevice)}” and its faces`}>
        Names of “{deviceName(namedDevice)}”…
      </button>
      <IdentifyToggle target={{ kind: "device", device: namedDevice.pos }} title="Light the device up in the world until turned off" />
    </div>
  {/if}

  <Field label="Label" hint={s.kind === "device" ? "Optional; the device's name is shown otherwise." : undefined}>
    <TextField value={node.label} disabled={!canEdit} placeholder="Optional" oncommit={(v) => save({ ...node, label: v || undefined }, "rename node")} />
  </Field>

  <Switch label="On" checked={node.enabled !== false} disabled={!canEdit} onchange={(on) => save({ ...node, enabled: on }, "node on/off")} />

  {#if !signal && s.kind !== "sheet_input" && s.kind !== "sheet_output"}
    <RedstoneEditor
      value={node.redstone}
      disabled={!canEdit}
      defaultLabel={s.kind === "device" ? "this device's connectors" : "the controller"}
      onchange={(redstone) => save({ ...node, redstone: redstone.mode === "ignored" ? undefined : redstone }, "redstone")}
    />
  {/if}

  {#if s.kind === "level"}
    <Field label="Device">
      <DevicePicker {devices} value={s.device} disabled={!canEdit} onchange={(d) => settings({ device: d.pos })} />
    </Field>
  {/if}
  {#if s.kind === "level"}
    <Field label="Resource">
      <select value={s.resource} disabled={!canEdit} onchange={(e) => settings({ resource: e.currentTarget.value as Resource })}>
        {#each Object.entries(RESOURCE_LABEL).filter(([key]) => moves(key as Resource)) as [key, label]}<option value={key}>{label}</option>{/each}
      </select>
    </Field>
    {#if s.resource !== "energy"}
      <Field label="Count only" hint="A filter; empty counts everything.">
        <FilterField value={s.filter ?? ""} disabled={!canEdit} kind={catalogKind(s.resource)} devices={[s.device]} onchange={(filter) => settings({ filter })} />
      </Field>
    {/if}
  {:else if s.kind === "compare"}
    <Field label="Test" hint="Gives 15 when true, else 0. B is the number below unless something is wired into B.">
      <div class="flex items-center gap-2">
        <span>A</span>
        <select value={s.op ?? "ge"} disabled={!canEdit} onchange={(e) => settings({ op: e.currentTarget.value as CompareOp })}>
          {#each Object.entries(OP_SYMBOL) as [key, symbol]}<option value={key}>{symbol}</option>{/each}
        </select>
        <NumberField value={s.constant ?? 1} min={-1_000_000_000} max={1_000_000_000} width="md" disabled={!canEdit} oncommit={(v) => settings({ constant: v ?? 0 })} />
      </div>
    </Field>
  {:else if s.kind === "logic"}
    <Field label="Operation" hint="Every wire into the input is one operand. NOT is on only while none is, so with several wires it is a NOR.">
      <select value={s.op ?? "and"} disabled={!canEdit} onchange={(e) => settings({ op: e.currentTarget.value as LogicOp })}>
        <option value="and">AND: all on</option>
        <option value="or">OR: any on</option>
        <option value="not">NOT / NOR: none on</option>
        <option value="xor">XOR: an odd number on</option>
        <option value="nand">NAND: not all on</option>
        <option value="xnor">XNOR: an even number on (two: they match)</option>
      </select>
    </Field>
  {:else if s.kind === "timer"}
    <Field label="Every" hint="Ticks between one-tick pulses (20 ticks = 1 second). Runs while its input is on or unwired.">
      <NumberField value={s.interval ?? 20} min={1} max={72_000} width="md" disabled={!canEdit} oncommit={(v) => settings({ interval: v })} />
    </Field>
  {:else if s.kind === "pulse"}
    <Field label="Length" hint="Ticks the pulse lasts.">
      <NumberField value={s.length ?? 1} min={1} max={72_000} width="md" disabled={!canEdit} oncommit={(v) => settings({ length: v })} />
    </Field>
  {:else if s.kind === "delay"}
    <Field label="Ticks" hint="How late the output follows the input. A loop of signal wires must pass a Delay, Latch or Counter.">
      <NumberField value={s.ticks ?? 1} min={1} max={1200} width="md" disabled={!canEdit} oncommit={(v) => settings({ ticks: v })} />
    </Field>
  {:else if s.kind === "latch" || s.kind === "counter"}
    <p class="text-xs muted">It remembers its value across restarts. Its output follows one tick after its inputs.</p>
  {:else if s.kind === "transmit" || s.kind === "receive"}
    <Field
      label="Signal name"
      hint={s.kind === "transmit"
        ? "Any sheet can pick it up with a Receiver node, and redstone conditions can use it. With several transmitters on one name, the highest wins."
        : "Receives the value transmitted on the previous tick."}
    >
      <TextField value={s.name} disabled={!canEdit} suggestions={names} placeholder="e.g. Furnaces full" oncommit={(v) => settings({ name: v })} />
    </Field>
  {/if}

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
      <button class="btn self-start text-sm" onclick={() => openSheet(used.id)}>Open “{used.name}”</button>
    {/if}
  {:else if s.kind === "sheet_input" || s.kind === "sheet_output"}
    <Field
      label="Pin name"
      hint={`Shown on the ${s.kind === "sheet_input" ? "left" : "right"} of sub-sheet nodes that use this sheet. Pins are ordered top to bottom as placed here.`}
    >
      <TextField value={s.name} disabled={!canEdit} placeholder="e.g. ores" oncommit={(v) => settings({ name: v })} />
    </Field>
  {/if}

  {#if s.kind === "filter"}
    <Field label="Filter" hint="Matches go out the green handle, everything else out the red one.">
      <FilterField
        value={s.expression}
        disabled={!canEdit}
        kind={catalogKind(resources(sheet).nodes.get(node.id))}
        devices={upstreamDevices(sheet, node.id)}
        onchange={(expression) => settings({ expression })}
      />
    </Field>
  {:else if s.kind === "rate_limit"}
    <Field label={app.rateUnit === "t" ? "Per tick" : "Per second"} hint={`${unit}; the top bar switches between per tick and per second.`}>
      <NumberField
        value={s.per_second}
        scale={app.rateUnit === "t" ? 1 / 20 : 1}
        min={1}
        max={1_000_000_000}
        width="lg"
        disabled={!canEdit}
        oncommit={(v) => settings({ per_second: v })}
      />
    </Field>
  {:else if s.kind === "splitter"}
    <Field
      label="Mode"
      hint="Each wire from the splitter's output is one branch. Round robin takes turns (fluids, chemicals and energy split evenly). In order fills the first branch before the next. Weighted shares by the weights below. Nearest first fills the branch whose device is closest to the source first. A branch that can't take anything is skipped."
    >
      <select value={s.mode ?? "round_robin"} disabled={!canEdit} onchange={(e) => settings({ mode: e.currentTarget.value as SplitMode })}>
        <option value="round_robin">Round robin</option>
        <option value="priority">In order</option>
        <option value="weighted">Weighted</option>
        <option value="nearest">Nearest first</option>
      </select>
    </Field>
    <!-- The branches in order: "in order" fills them top down, weights go with them, and ↑/↓ reorder them. -->
    {@const outs = splitterOutputs()}
    <fieldset class="flex flex-col gap-1">
      <legend class="field-label mb-1">Outputs, in order{s.mode === "weighted" ? " (weight of each)" : ""}</legend>
      {#each outs as wire, i (wire.id)}
        {@const target = sheet.nodes?.find((n) => n.id === wire.to)}
        <div class="flex items-center gap-1 text-sm">
          <span class="w-5 shrink-0 text-right tabular-nums muted">{i + 1}.</span>
          {#if s.mode === "weighted"}
            <NumberField
              value={s.weights?.[i] ?? 1}
              min={0}
              max={10_000}
              width="xs"
              disabled={!canEdit}
              title="Weight"
              oncommit={(v) => {
                const weights = outs.map((_, j) => s.weights?.[j] ?? 1);
                weights[i] = v ?? 0;
                settings({ weights });
              }}
            />
          {/if}
          <span class="min-w-0 flex-1 truncate">→ {nodeTitle(target)}</span>
          <button class="btn shrink-0 px-1 py-1" disabled={!canEdit || i === 0} title="Earlier" aria-label="Move up" onclick={() => moveOutput(i, -1)}><ArrowUp size={12} /></button>
          <button class="btn shrink-0 px-1 py-1" disabled={!canEdit || i === outs.length - 1} title="Later" aria-label="Move down" onclick={() => moveOutput(i, 1)}><ArrowDown size={12} /></button>
        </div>
      {:else}
        <p class="text-xs muted">Wire the splitter's output to two or more nodes; each wire is one branch.</p>
      {/each}
    </fieldset>
  {:else if s.kind === "kit"}
    <fieldset class="flex flex-col gap-2">
      <legend class="field-label mb-1">Items in one kit (all move together, or none)</legend>
      {#each s.lines as line, i}
        <div class="flex items-start gap-1">
          <NumberField
            value={line.count}
            min={1}
            max={100_000}
            width="xs"
            disabled={!canEdit}
            title="How many"
            oncommit={(v) => settings({ lines: s.lines.map((l, j) => (j === i ? { ...l, count: v ?? 1 } : l)) })}
          />
          <span class="pt-1 muted">×</span>
          <div class="min-w-0 flex-1">
            <FilterField value={line.filter} disabled={!canEdit} placeholder="e.g. iron_ingot" kind="item" devices={upstreamDevices(sheet, node.id)} onchange={(v) => settings({ lines: s.lines.map((l, j) => (j === i ? { ...l, filter: v } : l)) })} />
          </div>
          <RemoveButton class="mt-1" label="Remove item" disabled={!canEdit || s.lines.length === 1} onclick={() => settings({ lines: s.lines.filter((_, j) => j !== i) })} />
        </div>
      {/each}
      <button class="btn self-start text-sm" disabled={!canEdit || s.lines.length >= 64} onclick={() => settings({ lines: [...s.lines, { filter: "", count: 1 }] })}>+ Item</button>
      <Field label="Kits per operation">
        <NumberField value={s.max_kits ?? 1} min={1} max={64} disabled={!canEdit} oncommit={(v) => settings({ max_kits: v })} />
      </Field>
    </fieldset>
  {/if}

  {#if !signal}<Chart item={node.id} {unit} />{/if}
</InspectorPanel>
