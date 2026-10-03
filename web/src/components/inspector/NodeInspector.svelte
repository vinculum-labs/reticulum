<script lang="ts">
  import Help from "./Help.svelte";
  import Trash from "@lucide/svelte/icons/trash";
  import { app, deviceAt, edit, nodeTitle, openCopy, openDevice, openSheet, rebase, updateNodeEdit } from "../../lib/state.svelte";
  import { slotName, slotsOf } from "../../lib/blueprints";
  import { deviceChange } from "../../lib/prune";
  import { childSheets } from "../../lib/tree";
  import { KIND_HINT, KIND_LABEL, OP_SYMBOL, gated, sideLabel, moves, RESOURCE_LABEL, RESOURCE_UNIT, RESOURCE_VAR, isSignal } from "../../lib/kinds";
  import {
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
  import DeviceIcon from "../DeviceIcon.svelte";
  import { KIND_ICON } from "../../lib/icons";
  import SheetPins from "./SheetPins.svelte";
  import PinFields from "./PinFields.svelte";
  import { sideId } from "../../lib/nameUuid";
  import AddButton from "../controls/AddButton.svelte";
  import TextField from "../controls/TextField.svelte";

  let {
    node,
    sheet,
    inCopyAs,
  }: {
    node: GraphNode;
    sheet: Sheet;
    /** Shown inside an open copy: read-only, its activity kept under this id (the copy's name for it). */
    inCopyAs?: string;
  } = $props();

  const canEdit = $derived(!inCopyAs && (app.snapshot?.canEdit ?? false));
  /** Whose activity to show: its own, or the copy's name for it. A blueprint itself never runs: none. */
  const activityId = $derived(inCopyAs ?? (sheet.blueprint ? null : node.id));
  const s = $derived(node.settings);
  const signal = $derived(isSignal(s.kind));
  /** The device the node points at, whose names (and face names) can be edited. */
  const namedDevice = $derived(
    "device" in s ? deviceAt(posKey(s.device)) : undefined,
  );
  const devices = $derived(app.snapshot?.devices ?? []);
  /** Which side the graph shows ("in:0", "out:1"), or the whole node (""). */
  let graphSide = $state("");
  const graphChoices = $derived(
    s.kind === "device"
      ? [
          { value: "", label: "The whole device" },
          ...(s.inputs ?? []).map((x, i) => ({ value: `in:${i}`, label: `Input ${i + 1}: ${sideLabel(x)}` })),
          ...(s.outputs ?? []).map((x, i) => ({ value: `out:${i}`, label: `Output ${i + 1}: ${sideLabel(x)}` })),
        ]
      : [],
  );
  /** The side shown, while it still exists (one removed goes back to the whole device). */
  const graphKey = $derived(graphChoices.some((c) => c.value === graphSide) ? graphSide : "");
  const graphItem = $derived.by(() => {
    if (!graphKey) return activityId ?? node.id;
    const [direction, index] = graphKey.split(":");
    return sideId(activityId ?? node.id, direction as "in" | "out", Number(index));
  });
  const graphUnit = $derived.by(() => {
    if (!graphKey || s.kind !== "device") return unit;
    const [direction, index] = graphKey.split(":");
    const side = direction === "in" ? s.inputs?.[Number(index)] : s.outputs?.[Number(index)];
    return side ? RESOURCE_UNIT[side.resource] : unit;
  });

  /** A device node on a blueprint is a slot: a kind of block each copy fills, not a block of its own. */
  const slot = $derived(sheet.blueprint && s.kind === "device");
  const DeviceKindIcon = KIND_ICON.device;
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
    updateNodeEdit(sheet.id, node, next, label);
  }

  function settings(patch: Partial<NodeSettings>) {
    save({ ...node, settings: { ...node.settings, ...patch } as NodeSettings });
  }

  function splitterOutputs(): Wire[] {
    return splitterBranches(sheet, node.id);
  }

  /** Swaps a branch with its neighbor (a weighted splitter's weights are by wire, so they go with their branches). */
  function moveOutput(index: number, step: number) {
    const outs = splitterOutputs();
    const other = index + step;
    if (other < 0 || other >= outs.length || node.settings.kind !== "splitter") return;
    const ids = outs.map((w) => w.id);
    [ids[index], ids[other]] = [ids[other], ids[index]];
    const edits: Edit[] = [{ type: "orderWires", sheet: sheet.id, wires: ids }];
    const undo: Edit[] = [{ type: "orderWires", sheet: sheet.id, wires: outs.map((w) => w.id) }];
    edit(edits, undo, "reorder outputs");
  }

  const flow = useSvelteFlow();

  /** Deletes the node the way the Delete key does, with its wires, as one undoable change. */
  function remove() {
    flow.deleteElements({ nodes: [{ id: node.id }] });
  }

</script>

<InspectorPanel
  title={slot ? slotName({ ...node, label: undefined }) : KIND_LABEL[s.kind]}
  subtitle={slot ? "A slot of this blueprint: each copy fills it with a block of this kind. Its inputs and outputs here are every copy's." : KIND_HINT[s.kind]}
>
  {#snippet icon()}
    {#if slot}
      <!-- Faded, as on its card: the kind of block it takes, not a block. -->
      {#if s.kind === "device" && s.block}<span class="shrink-0" style="opacity: 0.5"><DeviceIcon item={s.block} size={28} /></span>
      {:else}{@const KindIcon = KIND_ICON[s.kind]}<span class="shrink-0 muted"><KindIcon size={24} /></span>{/if}
    {:else if s.kind === "device" && namedDevice}
      <DeviceIcon item={namedDevice.icon} size={28} />
    {:else}
      {@const KindIcon = KIND_ICON[s.kind]}
      <span class="shrink-0 muted"><KindIcon size={24} /></span>
    {/if}
  {/snippet}
  {#snippet actions()}
    {#if canEdit}<button class="btn btn-danger px-2" onclick={remove} title="Delete this node" aria-label="Delete node"><Trash size={16} /></button>{/if}
  {/snippet}

  {#if !slot && activityId}<ThroughputSummary id={activityId} {signal} />{/if}


  <!--
    One name each, as the tree and the other panels show it: a sheet node's is its sheet's and a device node's its
    device's (renaming either renames that), a pin's is its name below; any other node's is its own, optional.
  -->
  {#if s.kind === "sheet"}
    {@const named = app.snapshot?.sheets.find((x) => x.id === s.sheet)}
    {#if named}
      <Field label="Name">
        <TextField autofocus={app.nameNode === node.id} onfinish={() => app.nameNode === node.id && (app.nameNode = null)}
          value={named.name}
          required
          disabled={!canEdit}
          oncommit={(v) => edit([{ type: "updateSheet", sheet: named.id, name: v }], [{ type: "updateSheet", sheet: named.id, name: named.name }], "rename sheet")}
        />
      </Field>
    {/if}
  {:else if s.kind === "device" && !slot && namedDevice}
    <!-- The device's name, then its face names and its ID beside it, as each face is in the device panel. -->
    <Field label="Name" hint="The device's own name: the list and every panel show it.">
      <div class="flex min-w-0 items-center gap-2">
        <TextField autofocus={app.nameNode === node.id} onfinish={() => app.nameNode === node.id && (app.nameNode = null)}
          class="min-w-0 flex-1"
          value={namedDevice.name}
          placeholder={namedDevice.defaultName}
          disabled={!canEdit}
          oncommit={(v) => edit([{ type: "renameDevice", device: namedDevice.pos, name: v || null }], [{ type: "renameDevice", device: namedDevice.pos, name: namedDevice.name ?? null }], "rename device")}
        />
        <!-- Its faces' names live in its device panel: a cube for the block, as the toolbar's Device. -->
        <button class="btn px-2" onclick={() => openDevice(namedDevice)} title="Face names: its device panel" aria-label="Face names"><DeviceKindIcon size={16} /></button>
        <IdentifyToggle target={{ kind: "device", device: namedDevice.pos }} title="Light the device up in the world until turned off" />
      </div>
    </Field>
  {:else if s.kind === "copy"}
    <Field label="Name" hint="Optional — the blueprint's name is shown otherwise.">
      <TextField autofocus={app.nameNode === node.id} onfinish={() => app.nameNode === node.id && (app.nameNode = null)} value={node.label} disabled={!canEdit} placeholder={app.snapshot?.sheets.find((x) => x.id === s.sheet)?.name ?? "Optional"} oncommit={(v) => save({ ...node, label: v || undefined }, "rename copy")} />
    </Field>
  {:else if s.kind !== "sheet_input" && s.kind !== "sheet_output"}
    <Field label="Name" hint={slot ? "Optional — the kind of block is shown otherwise." : `Optional — “${KIND_LABEL[s.kind]}” is shown otherwise.`}>
      <TextField autofocus={app.nameNode === node.id} onfinish={() => app.nameNode === node.id && (app.nameNode = null)} value={node.label} disabled={!canEdit} placeholder="Optional" oncommit={(v) => save({ ...node, label: v || undefined }, "rename node")} />
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
      <Field label="Count only" hint="A filter — empty counts everything.">
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
    <Help>It remembers its value across restarts. Its output follows one tick after its inputs.</Help>
  {/if}

  {#if s.kind === "device"}
    <DeviceNodeEditor settings={s} {slot} {sheet} onshowside={(direction, index) => (graphSide = `${direction}:${index}`)} nodeId={node.id} disabled={!canEdit} onchange={saveDevice} />
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
      <Help>
        Its switch above switches everything in it, sheets inside included. A signal into its gate does too. Its pins are the
        sheet's: added here, or inside it, or by dragging a wire past its edge.
      </Help>
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
    <!-- The same fields as its group in the sheet's panel. -->
    <PinFields {sheet} pin={node} disabled={!canEdit} />
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
    <Field label={app.rateUnit === "t" ? "Per tick" : "Per second"} hint={`${unit} — the top bar switches between per tick and per second.`}>
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
              value={s.weights?.[wire.id] ?? 1}
              min={0}
              max={10_000}
              width="xs"
              disabled={!canEdit}
              title="Weight"
              oncommit={(v) => {
                // Only the branches it still has: weights of wires since removed are dropped.
                const weights = Object.fromEntries(outs.map((w) => [w.id, s.weights?.[w.id] ?? 1]));
                weights[wire.id] = v ?? 0;
                settings({ weights });
              }}
            />
          {/if}
          <span class="min-w-0 flex-1 truncate">→ {nodeTitle(target)}</span>
          <button class="btn shrink-0 px-1 py-1" disabled={!canEdit || i === 0} title="Earlier" aria-label="Move up" onclick={() => moveOutput(i, -1)}><ArrowUp size={12} /></button>
          <button class="btn shrink-0 px-1 py-1" disabled={!canEdit || i === outs.length - 1} title="Later" aria-label="Move down" onclick={() => moveOutput(i, 1)}><ArrowDown size={12} /></button>
        </div>
      {:else}
        <p class="text-xs muted">Wire the splitter's output to two or more nodes — each wire is one branch.</p>
      {/each}
    </fieldset>
  {:else if s.kind === "kit"}
    <fieldset class="flex flex-col gap-2">
      <div class="flex items-center justify-between">
        <legend class="field-label">Items in one kit (all move together, or none)</legend>
        <AddButton label="Add an item" disabled={!canEdit || s.lines.length >= 64} onclick={() => settings({ lines: [...s.lines, { filter: "", count: 1 }] })} />
      </div>
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
      <Field label="Kits per operation">
        <NumberField value={s.max_kits ?? 1} min={1} max={64} disabled={!canEdit} oncommit={(v) => settings({ max_kits: v })} />
      </Field>
    </fieldset>
  {/if}

  {#if !signal && activityId}
    {#if s.kind === "device" && !slot && graphChoices.length > 2}
      <!-- One graph: the whole device's, or one input's or output's (picked here, or by the rate in its header). -->
      <Field label="Graph of">
        <Select value={graphKey} options={graphChoices} onchange={(v) => (graphSide = v)} />
      </Field>
    {/if}
    <Chart item={graphItem} unit={graphUnit} />
  {/if}
</InspectorPanel>
