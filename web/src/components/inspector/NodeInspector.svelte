<script lang="ts">
  import { app, deviceAt, deviceName, edit, nodeTitle, openCopy, openDevice, openSheet, rebase } from "../../lib/state.svelte";
  import { slotsOf } from "../../lib/blueprints";
  import { deviceChange } from "../../lib/prune";
  import { childSheets } from "../../lib/tree";
  import { KIND_HINT, KIND_LABEL, OP_SYMBOL, PIN_CARRIES, gated, meUsageLabel, moves, RESOURCE_LABEL, RESOURCE_UNIT, RESOURCE_VAR, isSignal } from "../../lib/kinds";
  import {
    SIDES,
    posKey,
    pinName,
    pinSlot,
    sheetPins,
    GATE,
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
  import Segmented from "../controls/Segmented.svelte";
  import Field from "./Field.svelte";
  import FilterField from "./FilterField.svelte";
  import { catalogKind } from "../../lib/catalog";
  import { resources, splitterBranches, upstreamDevices } from "../../lib/graph";
  import { useSvelteFlow } from "@xyflow/svelte";
  import ArrowDown from "@lucide/svelte/icons/arrow-down";
  import ArrowUp from "@lucide/svelte/icons/arrow-up";
  import Scissors from "@lucide/svelte/icons/scissors";
  import DeviceNodeEditor from "./DeviceNodeEditor.svelte";
  import Chart from "./Chart.svelte";
  import InspectorPanel from "./InspectorPanel.svelte";
  import ThroughputSummary from "./ThroughputSummary.svelte";
  import IdentifyToggle from "../controls/IdentifyToggle.svelte";
  import NumberField from "../controls/NumberField.svelte";
  import Switch from "../controls/Switch.svelte";
  import RemoveButton from "../controls/RemoveButton.svelte";
  import Select from "../controls/Select.svelte";
  import WhileOffField from "./WhileOffField.svelte";
  import DeviceIcon from "../DeviceIcon.svelte";
  import { KIND_ICON } from "../../lib/icons";
  import SheetPins from "./SheetPins.svelte";
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
  const gateWired = $derived((sheet.wires ?? []).some((w) => w.to === node.id && (w.input ?? 0) === GATE));
  const unit = $derived(s.kind === "device" && s.outputs?.[0] ? RESOURCE_UNIT[s.outputs[0].resource] : "units");

  /** The held sheet's pin nodes nothing outside is wired to: no wire on their slot of this sheet node. */
  function unwiredPins(held: Sheet): GraphNode[] {
    const wires = sheet.wires ?? [];
    const wired = (input: boolean, slot: number) =>
      wires.some((w) => (input ? w.to === node.id && (w.input ?? 0) === slot : w.from === node.id && (w.output ?? 0) === slot));
    return [true, false].flatMap((input) => sheetPins(held, input).filter((pin, i) => !wired(input, pinSlot(pin, i))));
  }

  /** Removes the held sheet's unwired pins (their wires inside go too; undo puts both back). */
  function prunePins(held: Sheet) {
    const gone = unwiredPins(held);
    const ids = new Set(gone.map((n) => n.id));
    const inner = (held.wires ?? []).filter((w) => ids.has(w.from) || ids.has(w.to));
    edit(
      gone.map((n) => ({ type: "removeNode", sheet: held.id, node: n.id }) as Edit),
      [...gone.map((n) => ({ type: "addNode", sheet: held.id, node: n }) as Edit), ...inner.map((wire) => ({ type: "connect", sheet: held.id, wire }) as Edit)],
      "prune pins",
    );
  }

  /**
   * Saves a device node. Removed inputs and outputs (by their old index) lose their wires, and wires on the ones
   * after them are renumbered (the server identifies them by number).
   */
  function saveDevice(next: DeviceNodeSettings, removed?: { in?: number[]; out?: number[] }, label = "edit device") {
    const { after, before } = rebase(node, { ...node, settings: next });
    const { edits, undo } = deviceChange(sheet, before, after, removed);
    edit(edits, undo, label);
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
  {#snippet icon()}
    {#if s.kind === "device" && namedDevice}
      <DeviceIcon item={namedDevice.icon} size={28} />
    {:else}
      {@const KindIcon = KIND_ICON[s.kind]}
      <span class="shrink-0 muted"><KindIcon size={24} /></span>
    {/if}
  {/snippet}
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

  <!-- A pin's name is its Pin name, below: one name, not two. -->
  {#if s.kind !== "sheet_input" && s.kind !== "sheet_output"}
  <Field label="Label" hint={s.kind === "device" ? "Optional; the device's name is shown otherwise." : undefined}>
    <TextField value={node.label} disabled={!canEdit} placeholder="Optional" oncommit={(v) => save({ ...node, label: v || undefined }, "rename node")} />
  </Field>
  {/if}

  <Switch label="On" checked={node.enabled !== false} disabled={!canEdit} onchange={(on) => save({ ...node, enabled: on }, "node on/off")} />

  {#if gated(s.kind)}
    <Field
      label="Gate"
      hint={gateWired
        ? "While on: runs while the signal into its gate is above 0. One per pulse: one operation each time it turns on (a one-tick pulse would usually miss a While on gate)."
        : "Wire a signal into the diamond pin on its left to switch it. While on: runs while the signal is above 0. One per pulse: one operation each time it turns on."}
    >
      <Segmented
        label="Gate"
        size="xs"
        value={node.gate ?? "while_on"}
        disabled={!canEdit}
        options={[
          { value: "while_on", label: "While on" },
          { value: "pulse", label: "One operation per pulse" },
        ]}
        onchange={(gate) => save({ ...node, gate: gate === "while_on" ? undefined : (gate as "pulse") }, "gate mode")}
      />
    </Field>
  {/if}

  {#if s.kind === "level"}
    <Field label="Device">
      <DevicePicker {devices} value={s.device} disabled={!canEdit} onchange={(d) => settings({ device: d.pos })} />
    </Field>
  {/if}
  {#if s.kind === "level"}
    <Field label="Resource">
      <Select
        value={s.resource}
        disabled={!canEdit}
        options={Object.entries(RESOURCE_LABEL).filter(([key]) => moves(key as Resource)).map(([key, label]) => ({ value: key as Resource, label, swatch: RESOURCE_VAR[key as Resource] }))}
        onchange={(resource) => settings({ resource })}
      />
    </Field>
    {#if s.resource !== "energy"}
      <Field label="Count only" hint="A filter; empty counts everything.">
        <FilterField value={s.filter ?? ""} disabled={!canEdit} kind={catalogKind(s.resource)} devices={[s.device]} onchange={(filter) => settings({ filter })} />
      </Field>
    {/if}
    <Field
      label="Output"
      hint={s.output === "range"
        ? "0 at or below the first amount, 15 at or above the second, and in steps between: for a lamp, a comparator line or a gate."
        : s.output === "comparator"
          ? "How full the device is (only what is counted), as a comparator on it would say: 0 when empty, 15 when full."
          : "The amount itself, for a Compare (\"at least 1000\") or to count with. A device's redstone input gets at most 15 of it."}
    >
      <Segmented
        label="Output"
        size="xs"
        value={s.output ?? "amount"}
        disabled={!canEdit}
        options={[
          { value: "amount", label: "Amount" },
          { value: "range", label: "0–15 between" },
          // Mekanism's chemical tanks aren't measured for fullness.
          ...(s.resource !== "chemical" ? [{ value: "comparator", label: "Like a comparator" }] : []),
        ]}
        onchange={(v) => {
          const output = v as "amount" | "range" | "comparator";
          settings(output === "range" && !s.high ? { output, low: 0, high: 1000 } : { output: output === "amount" ? undefined : output });
        }}
      />
    </Field>
    {#if s.output === "range"}
      <Field label={`From ${RESOURCE_UNIT[s.resource]} (gives 0) to (gives 15)`}>
        <div class="flex items-center gap-2">
          <NumberField value={s.low ?? 0} min={0} integer width="md" disabled={!canEdit} title="At or below this: 0" oncommit={(v) => settings({ low: v ?? 0 })} />
          <span class="muted">to</span>
          <NumberField value={s.high ?? 0} min={0} integer width="md" disabled={!canEdit} title="At or above this: 15" oncommit={(v) => settings({ high: v ?? 0 })} />
        </div>
      </Field>
    {/if}
  {:else if s.kind === "compare"}
    <Field label="Test" hint="Gives 15 when true, else 0. B is the number below unless something is wired into B.">
      <div class="flex items-center gap-2">
        <span>A</span>
        <Select
          value={s.op ?? "ge"}
          disabled={!canEdit}
          options={Object.entries(OP_SYMBOL).map(([key, symbol]) => ({ value: key as CompareOp, label: symbol }))}
          onchange={(op) => settings({ op })}
        />
        <NumberField value={s.constant ?? 1} min={-1_000_000_000} max={1_000_000_000} width="md" disabled={!canEdit} oncommit={(v) => settings({ constant: v ?? 0 })} />
      </div>
    </Field>
  {:else if s.kind === "logic"}
    <Field label="Operation" hint="Every wire into the input is one operand. NOT is on only while none is, so with several wires it is a NOR.">
      <Select
        value={s.op ?? "and"}
        disabled={!canEdit}
        options={[
          { value: "and" as LogicOp, label: "AND: all on" },
          { value: "or" as LogicOp, label: "OR: any on" },
          { value: "not" as LogicOp, label: "NOT / NOR: none on" },
          { value: "xor" as LogicOp, label: "XOR: an odd number on" },
          { value: "nand" as LogicOp, label: "NAND: not all on" },
          { value: "xnor" as LogicOp, label: "XNOR: an even number on (two: they match)" },
        ]}
        onchange={(op) => settings({ op })}
      />
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
  {/if}

  {#if s.kind === "device"}
    <DeviceNodeEditor settings={s} {sheet} nodeId={node.id} disabled={!canEdit} onchange={saveDevice} />
  {/if}

  {#if s.kind === "sheet"}
    {@const held = app.snapshot?.sheets.find((x) => x.id === s.sheet)}
    {#if held}
      {@const inside = app.snapshot ? childSheets(app.snapshot, held.id).length : 0}
      <Field label="Sheet name">
        <TextField
          value={held.name}
          required
          disabled={!canEdit}
          oncommit={(name) => edit([{ type: "updateSheet", sheet: held.id, name }], [{ type: "updateSheet", sheet: held.id, name: held.name }], "rename sheet")}
        />
      </Field>
      <div class="text-sm">
        <div><span class="muted">Holds:</span> {held.nodes?.length ?? 0} node{(held.nodes?.length ?? 0) === 1 ? "" : "s"}{inside ? `, ${inside} sheet${inside === 1 ? "" : "s"}` : ""}</div>
      </div>
      <p class="text-xs muted">
        Its switch above switches everything in it, sheets inside included; so does a signal into its gate. Its pins are the
        sheet's: added here, or inside it, or by dragging a wire past its edge.
      </p>
      <SheetPins sheet={held} />
      {@const unwired = unwiredPins(held).length}
      <div class="flex flex-wrap gap-2">
        <button class="btn text-sm" onclick={() => openSheet(held.id)}>Open “{held.name}”</button>
        <button
          class="btn flex items-center gap-1 text-sm"
          disabled={!canEdit || unwired === 0}
          title={unwired ? `Remove the ${unwired} pin${unwired === 1 ? "" : "s"} nothing here is wired to (the pin nodes inside the sheet)` : "Every pin is wired"}
          onclick={() => prunePins(held)}><Scissors size={14} />Prune pins{unwired ? ` (${unwired})` : ""}</button
        >
      </div>
    {:else}
      <p class="text-sm text-bad">The sheet this node held is gone.</p>
    {/if}
  {:else if s.kind === "copy"}
    {@const choices = (app.snapshot?.sheets ?? []).filter((x) => x.blueprint && x.id !== sheet.id)}
    {@const used = app.snapshot?.sheets.find((x) => x.id === s.sheet)}
    <Field label="Blueprint" hint="Double-click the node to open the copy and fill its slots. Another blueprint empties them.">
      <Select
        value={s.sheet}
        disabled={!canEdit}
        placeholder="(missing)"
        options={choices.map((choice) => ({ value: choice.id, label: choice.name }))}
        onchange={(sheet) => settings({ sheet, bindings: undefined })}
      />
    </Field>
    {#if used}
      {@const slots = slotsOf(used)}
      {@const filled = slots.filter((x) => s.bindings?.[x.id]).length}
      <div class="text-sm">
        <div><span class="muted">Inputs:</span> {sheetPins(used, true).map(pinName).join(", ") || "none"}</div>
        <div><span class="muted">Outputs:</span> {sheetPins(used, false).map(pinName).join(", ") || "none"}</div>
        {#if slots.length}
          <div class={filled < slots.length && !sheet.blueprint ? "text-bad" : ""}><span class="muted">Slots filled:</span> {filled} of {slots.length}</div>
        {/if}
      </div>
      <div class="flex flex-wrap gap-2">
        {#if !sheet.blueprint}
          <button class="btn text-sm" onclick={() => openCopy(sheet.id, node.id)}>Open the copy</button>
        {/if}
        <button class="btn text-sm" onclick={() => openSheet(used.id)}>Edit the blueprint</button>
      </div>
    {/if}
  {:else if s.kind === "sheet_input" || s.kind === "sheet_output"}
    <Field
      label="Pin name"
      hint={`Shown by its dot on the sheet's ${s.kind === "sheet_input" ? "left" : "right"} edge, and on the sheet's node. Drag the pin up or down to reorder.`}
    >
      <!-- A name once set as the node's label shows here too, and moves into the pin's name when edited. -->
      <TextField value={s.name || node.label} disabled={!canEdit} placeholder="e.g. ores"
        oncommit={(v) => save({ ...node, label: undefined, settings: { ...s, name: v } }, "rename pin")} />
    </Field>
    <Field label="Carries" hint="Wires of other kinds won't connect to it. Anything: whatever is wired to it.">
      <Select
        value={s.resource ?? ""}
        disabled={!canEdit}
        options={PIN_CARRIES}
        onchange={(v) => settings({ resource: (v || undefined) as Resource | undefined })}
      />
    </Field>
    {#if s.kind === "sheet_output" && (!s.resource || s.resource === "redstone")}
      <WhileOffField value={s.while_off} disabled={!canEdit} onchange={(while_off) => settings({ while_off })} />
    {/if}
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
      <Select
        value={s.mode ?? "round_robin"}
        disabled={!canEdit}
        options={[
          { value: "round_robin" as SplitMode, label: "Round robin" },
          { value: "priority" as SplitMode, label: "In order" },
          { value: "weighted" as SplitMode, label: "Weighted" },
          { value: "nearest" as SplitMode, label: "Nearest first" },
        ]}
        onchange={(mode) => settings({ mode })}
      />
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
