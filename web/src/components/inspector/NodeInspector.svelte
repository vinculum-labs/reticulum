<script lang="ts">
  import Help from "./Help.svelte";
  import Trash from "@lucide/svelte/icons/trash";
  import { app, deviceAt, edit, nodeTitle, openInstance, openDevice, openSheet, rebase, updateNodeEdit } from "../../lib/state.svelte";
  import { slotName, slotsOf, allSlotKeys } from "../../lib/blueprints";
  import { deviceChange } from "../../lib/prune";
  import { childSheets } from "../../lib/tree";
  import { KIND_HINT, KIND_LABEL, OP_SYMBOL, gated, sideLabel, moves, RESOURCE_LABEL, RESOURCE_UNIT, RESOURCE_VAR, isSignal, NAME_LIMIT } from "../../lib/kinds";
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
  import SequencerGrid from "./SequencerGrid.svelte";
  import ControlWidget from "./ControlWidget.svelte";
  import IconPicker from "./IconPicker.svelte";
  import ColorField from "../controls/ColorField.svelte";
  import ColorButton from "../controls/ColorButton.svelte";
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
    inInstanceAs,
  }: {
    node: GraphNode;
    sheet: Sheet;
    /**
     * Shown inside an open instance: read-only, its activity kept under this id (the instance's name for it), or null
     * for an instance that doesn't run (one opened inside a blueprint): no activity at all.
     */
    inInstanceAs?: string | null;
  } = $props();

  const canEdit = $derived(inInstanceAs === undefined && (app.snapshot?.canEdit ?? false));
  /** Whose activity to show: its own, or the instance's name for it. A blueprint itself never runs: none. */
  const activityId = $derived(inInstanceAs !== undefined ? inInstanceAs : sheet.blueprint ? null : node.id);
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

  /** A device node on a blueprint is a slot: a kind of block each instance fills, not a block of its own. */
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
  async function saveDevice(next: DeviceNodeSettings, removed?: { in?: number[]; out?: number[] }, label = "edit device") {
    const shown = graphSide;
    const { after, before } = rebase(node, { ...node, settings: next });
    const { edits, undo } = deviceChange(sheet, before, after, removed);
    const ok = await edit(edits, undo, label);
    // Done: the graph shown follows its side when sides before it went (and goes when its own did).
    const [direction, at] = shown.split(":");
    const gone = direction === "in" ? removed?.in : direction === "out" ? removed?.out : undefined;
    if (ok && graphSide === shown && gone?.length && at !== undefined) {
      const index = Number(at);
      graphSide = gone.includes(index) ? "" : `${direction}:${index - gone.filter((i) => i < index).length}`;
    }
  }

  function save(next: GraphNode, label = "edit node") {
    updateNodeEdit(sheet.id, node, next, label);
  }

  /** Removes a Sequencer track: its wire goes, and the wires of the tracks after it move up with them. */
  function removeTrack(index: number) {
    if (node.settings.kind !== "sequencer") return;
    const tracks = (node.settings.tracks ?? []).filter((_, i) => i !== index);
    const { after, before } = rebase(node, { ...node, settings: { ...node.settings, tracks } });
    const { edits, undo } = deviceChange(sheet, before, after, { out: [index + 1] });
    edit(edits, undo, "remove track");
  }

  /**
   * Places another blueprint here (its slots start empty). A numbered name the old blueprint gave it ("Smelter #2") goes,
   * so the game numbers it for the new one; a name typed in stays.
   */
  function switchBlueprint(sheet: string) {
    if (node.settings.kind !== "instance") return;
    const old = app.snapshot?.sheets.find((x) => x.id === (node.settings as { sheet?: string }).sheet)?.name;
    const numbered = !!old && !!node.label?.startsWith(`${old} #`) && /^\d+$/.test(node.label.slice(old.length + 2));
    save({ ...node, label: numbered ? undefined : node.label, settings: { ...node.settings, sheet, bindings: undefined, layout: undefined } as NodeSettings }, "change blueprint");
  }

  /** Another placement of the same blueprint with this one's name. */
  const sameName = $derived.by(() => {
    if (s.kind !== "instance" || !node.label) return false;
    const label = node.label.trim().toLowerCase();
    return (app.snapshot?.sheets ?? []).some((sh) =>
      (sh.nodes ?? []).some((n) => n.id !== node.id && n.settings.kind === "instance" && n.settings.sheet === s.sheet && n.label?.trim().toLowerCase() === label),
    );
  });

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
  subtitle={slot ? "A slot of this blueprint: wherever it's placed, a block of this kind fills it. Its inputs and outputs here apply in every place." : KIND_HINT[s.kind]}
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
  {:else if s.kind === "instance"}
    <Field label="Name" hint="Placed, it is numbered (“Smelter #2”) — rename it as you like — cleared, it gets a number again.">
      <TextField autofocus={app.nameNode === node.id} onfinish={() => app.nameNode === node.id && (app.nameNode = null)} value={node.label} maxlength={NAME_LIMIT} disabled={!canEdit} placeholder={app.snapshot?.sheets.find((x) => x.id === s.sheet)?.name ?? "Optional"} oncommit={(v) => save({ ...node, label: v || undefined }, "rename instance")} />
      {#if sameName}
        <p class="text-xs text-warn" role="status">Another placement of this blueprint is also called “{node.label}” — on the dashboard and in the tree they look alike.</p>
      {/if}
    </Field>
  {:else if s.kind !== "sheet_input" && s.kind !== "sheet_output"}
    <Field label="Name" hint={slot ? "Optional — the kind of block is shown otherwise." : `Optional — “${KIND_LABEL[s.kind]}” is shown otherwise.`}>
      <TextField autofocus={app.nameNode === node.id} onfinish={() => app.nameNode === node.id && (app.nameNode = null)} value={node.label} maxlength={NAME_LIMIT} disabled={!canEdit} placeholder="Optional" oncommit={(v) => save({ ...node, label: v || undefined }, "rename node")} />
    </Field>
  {/if}

  <Field label="Note" hint="What it is for: shown in its card's tooltip, and under its name on the dashboard.">
    <TextField value={node.note ?? ""} placeholder="Optional" disabled={!canEdit} oncommit={(v) => save({ ...node, note: v.trim().slice(0, 200) || undefined }, "note")} />
  </Field>

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
    {#if sheet.blueprint}
      <!-- On a blueprint it reads a slot, by the slot's id: in each instance, the block filling it. Or one fixed device. -->
      {@const slots = slotsOf(sheet)}
      <Field label="Reads" hint="A slot: wherever the blueprint is placed, it reads the block filling that slot there. A fixed device: the same block everywhere.">
        <Select
          value={s.slot ?? ""}
          disabled={!canEdit}
          options={[...slots.map((slot) => ({ value: slot.id, label: slotName(slot) })), { value: "", label: "A fixed device" }]}
          onchange={(slot) => {
            const picked = slots.find((x) => x.id === slot);
            settings(picked ? { slot: picked.id, device: picked.settings.device } : { slot: undefined });
          }}
        />
      </Field>
    {/if}
    {#if !sheet.blueprint || !s.slot}
      <Field label="Device">
        <DevicePicker {devices} value={s.device} disabled={!canEdit} onchange={(d) => settings({ device: d.pos })} />
      </Field>
    {/if}
  {/if}
  {#if s.kind === "level"}
    <Field label="Resource">
      <Select
        value={s.resource}
        disabled={!canEdit}
        options={Object.entries(RESOURCE_LABEL).filter(([key]) => moves(key as Resource)).map(([key, label]) => ({ value: key as Resource, label, swatch: RESOURCE_VAR[key as Resource] }))}
        onchange={(resource) => settings(resource === "chemical" && s.output === "comparator" ? { resource, output: undefined } : { resource })}
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
  {:else if s.kind === "constant"}
    <Field label="Value" hint="What it gives while its input is on (or unwired): 0 to 15 for redstone (more is 15 to a device), or any number to count with or compare against. Its switch above turns it off (0) and on.">
      <NumberField value={s.value ?? 15} min={0} max={1_000_000_000} width="md" disabled={!canEdit} oncommit={(v) => settings({ value: v ?? 0 })} />
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
  {:else if s.kind === "sequencer"}
    <Field label="Steps" hint="How many steps it goes through (up to 256).">
      <div class="flex items-center gap-2">
        <NumberField value={s.steps ?? 16} min={1} max={256} width="md" disabled={!canEdit} oncommit={(v) => settings({ steps: v ?? 16 })} />
        <span class="muted">×</span>
        <NumberField value={s.step_ticks ?? 4} min={1} max={1200} width="md" disabled={!canEdit} oncommit={(v) => settings({ step_ticks: v ?? 4 })} />
        <span class="muted">ticks each ({(20 / (s.step_ticks ?? 4)).toFixed(1)} steps a second)</span>
      </div>
    </Field>
    <Field label="Outputs" hint="Pulse: one tick as each step starts, so a note block plays each hit (two hits in a row need 2+ ticks a step). Hold: the value for the whole step.">
      <div class="flex items-center gap-3">
        <Segmented
          label="Outputs"
          size="xs"
          value={s.pulse === false ? "hold" : "pulse"}
          disabled={!canEdit}
          options={[
            { value: "pulse", label: "Pulse" },
            { value: "hold", label: "Hold" },
          ]}
          onchange={(v) => settings({ pulse: v === "pulse" })}
        />
        <Switch label="Loop" checked={s.loop !== false} disabled={!canEdit} onchange={(on) => settings({ loop: on })} />
      </div>
    </Field>
    <SequencerGrid
      tracks={s.tracks ?? []}
      steps={s.steps ?? 16}
      playing={activityId ? (app.signals[activityId] ?? 0) : 0}
      {canEdit}
      onchange={(tracks) => settings({ tracks })}
      onremove={(i) => removeTrack(i)}
    />
    {#if canEdit && (s.tracks?.length ?? 0) < 16}
      <button class="btn self-start" onclick={() => settings({ tracks: [...(s.tracks ?? []), { name: "", cells: "" }] })}>Add track</button>
    {/if}
    <Help>Wire each track to a device's redstone input — a note block per note. "run" (always on while unwired) plays it; "reset" goes back to step 1 — without Loop it plays once, then waits for "reset" before "run" plays it again. The highlighted step updates about once a second.</Help>
  {:else if s.kind === "switch"}
    <Field label="On gives" hint="What it gives while on (0 while off). A level switch is set to anything from 0 to this.">
      <NumberField value={s.value ?? 15} min={1} max={1_000_000} width="md" disabled={!canEdit} oncommit={(v) => settings({ value: v ?? 15 })} />
    </Field>
    <Field label="Set by">
      <Segmented
        label="Set by"
        size="xs"
        value={s.level ? "level" : "toggle"}
        disabled={!canEdit}
        options={[
          { value: "toggle", label: "On / off" },
          { value: "level", label: "Level" },
        ]}
        onchange={(v) => settings({ level: v === "level" })}
      />
    </Field>
    <Field label="Icon" hint="Shown beside it on the dashboard.">
      <IconPicker value={s.icon} disabled={!canEdit} onchange={(icon) => settings({ icon })} />
    </Field>
    {#if activityId}
      <Field label="Now">
        <ControlWidget control={{ node, sheet, run: activityId }} label={nodeTitle(node)} canEdit={app.snapshot?.canEdit ?? false} />
      </Field>
    {/if}
    <Help>Flip it here or on the dashboard (anyone who can edit the network can). It keeps where it stands across restarts — in a blueprint, separately for each place it's placed.</Help>
  {:else if s.kind === "display"}
    <Field label="Shows as">
      <Segmented
        label="Shows as"
        size="xs"
        value={s.style ?? "number"}
        disabled={!canEdit}
        options={[
          { value: "number", label: "Number" },
          { value: "gauge", label: "Gauge" },
          { value: "light", label: "Light" },
          { value: "message", label: "Message" },
        ]}
        onchange={(style) => settings(style === "message" && !s.messages?.length ? { style, messages: [{ from: 0, text: "Off" }, { from: 1, text: "On" }] } : { style })}
      />
    </Field>
    <Field label="Icon" hint="Shown beside it on the dashboard.">
      <IconPicker value={s.icon} disabled={!canEdit} onchange={(icon) => settings({ icon })} />
    </Field>
    {#if s.style === "message"}
      {@const messages = s.messages ?? []}
      <Field label="Messages" hint={"Each from its value up to the next one's; {value} in the text shows the value. Below the first, the number shows."}>
        <div class="flex flex-col gap-1">
          {#each messages as m, i (i)}
            <div class="flex items-center gap-1">
              <NumberField
                value={m.from ?? 0}
                min={-1_000_000_000_000}
                max={1_000_000_000_000}
                width="sm"
                disabled={!canEdit}
                oncommit={(v) => settings({ messages: messages.map((x, j) => (j === i ? { ...x, from: v ?? 0 } : x)) })}
              />
              <TextField
                class="min-w-0 flex-1"
                value={m.text ?? ""}
                placeholder={"Text — {value} shows the value"}
                disabled={!canEdit}
                oncommit={(v) => settings({ messages: messages.map((x, j) => (j === i ? { ...x, text: v.slice(0, 64) } : x)) })}
              />
              <ColorButton
                value={m.color}
                label={`Color of “${m.text || "this message"}”`}
                disabled={!canEdit}
                onchange={(color) => settings({ messages: messages.map((x, j) => (j === i ? { ...x, color } : x)) })}
              />
              {#if canEdit}<RemoveButton label="Remove this message" onclick={() => settings({ messages: messages.filter((_, j) => j !== i) })} />{/if}
            </div>
          {/each}
          {#if canEdit && messages.length < 16}
            {@const next = messages.length ? Math.max(...messages.map((m) => m.from ?? 0)) + 1 : 0}
            <button class="btn self-start text-xs" onclick={() => settings({ messages: [...messages, { from: next, text: "" }] })}>Add message</button>
          {/if}
        </div>
      </Field>
    {:else}
      <Field label="Color" hint={s.style === "gauge" ? "The gauge's fill." : s.style === "light" ? "The light, lit." : "The number."}>
        <ColorField value={s.color} disabled={!canEdit} onchange={(color) => settings({ color })} />
      </Field>
    {/if}
    {#if (s.style ?? "number") === "number" || s.style === "gauge"}
      <Field label="Unit" hint="After the number: items, mB, %…">
        <TextField value={s.unit ?? ""} placeholder="none" disabled={!canEdit} oncommit={(v) => settings({ unit: v.slice(0, 16) })} />
      </Field>
    {/if}
    {#if s.style === "gauge"}
      <Field label="Full at" hint="The value that fills the gauge.">
        <NumberField value={s.max ?? 15} min={1} max={1_000_000_000_000} width="md" disabled={!canEdit} oncommit={(v) => settings({ max: v ?? 15 })} />
      </Field>
    {/if}
    {#if activityId}
      <Field label="Now">
        <ControlWidget control={{ node, sheet, run: activityId }} label={nodeTitle(node)} canEdit={false} />
      </Field>
    {/if}
    <Help>Shown on the dashboard with the node's name. It shows the highest value wired into it — a Level sensor's amount, a Counter, a Compare — and passes it on.</Help>
  {:else if s.kind === "alarm"}
    <Field label="Severity">
      <Segmented
        label="Severity"
        size="xs"
        value={s.severity ?? "warning"}
        disabled={!canEdit}
        options={[
          { value: "warning", label: "Warning" },
          { value: "critical", label: "Critical" },
        ]}
        onchange={(severity) => settings({ severity })}
      />
    </Field>
    <Field label="Message" hint="What the dashboard (and beacons) say while it is going off. Blank: the node's name.">
      <TextField value={s.message ?? ""} placeholder={node.label || "Alarm"} disabled={!canEdit} oncommit={(v) => settings({ message: v.slice(0, 200) })} />
    </Field>
    <Field label="Goes off when" hint="The input against a number (a Level sensor's amount, say), or just while the input is on. Unwired, it never goes off.">
      <div class="flex items-center gap-2">
        <span>in</span>
        <Select
          value={s.op ?? "on"}
          disabled={!canEdit}
          options={[{ value: "on", label: "is on" }, ...Object.entries(OP_SYMBOL).map(([key, symbol]) => ({ value: key, label: symbol }))]}
          onchange={(op) => settings({ op: op === "on" ? undefined : (op as CompareOp) })}
        />
        {#if s.op}
          <NumberField value={s.threshold ?? 0} min={-1_000_000_000_000} max={1_000_000_000_000} width="md" disabled={!canEdit} oncommit={(v) => settings({ threshold: v ?? 0 })} />
        {/if}
      </div>
    </Field>
    <Field label="For (seconds)" hint="Goes off only once that has held this long, so a dip that recovers at once raises nothing.">
      <NumberField value={s.delay ?? 0} min={0} max={86_400} width="md" disabled={!canEdit} oncommit={(v) => settings({ delay: v ?? 0 })} />
    </Field>
    <Help>While going off it gives 15 if critical, 7 if a warning: wire it to a device's redstone input — a siren, a lamp, or a Status Beacon in Redstone mode, which shows that severity. Switch the node off to mute it.</Help>
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
  {:else if s.kind === "instance"}
    {@const choices = (app.snapshot?.sheets ?? []).filter((x) => x.blueprint && x.id !== sheet.id)}
    {@const used = app.snapshot?.sheets.find((x) => x.id === s.sheet)}
    <Field label="Blueprint" hint="Double-click the node to open it and fill its slots. Another blueprint empties them.">
      <Select
        value={s.sheet}
        disabled={!canEdit}
        placeholder="(missing)"
        options={choices.map((choice) => ({ value: choice.id, label: choice.name }))}
        onchange={(sheet) => switchBlueprint(sheet)}
      />
    </Field>
    {#if used}
      {@const slots = allSlotKeys(used, app.snapshot?.sheets ?? [])}
      {@const filled = slots.filter((key) => s.bindings?.[key]).length}
      <div class="text-sm">
        <div><span class="muted">Inputs:</span> {sheetPins(used, true).map(pinName).join(", ") || "none"}</div>
        <div><span class="muted">Outputs:</span> {sheetPins(used, false).map(pinName).join(", ") || "none"}</div>
        {#if slots.length && sheet.blueprint}
          <div><span class="muted">Slots:</span> {slots.length}, filled by each placement of this blueprint</div>
        {:else if slots.length}
          <div class={filled < slots.length ? "text-bad" : ""}><span class="muted">Slots filled:</span> {filled} of {slots.length}</div>
        {/if}
      </div>
      <div class="flex flex-wrap gap-2">
        {#if !sheet.blueprint}
          <button class="btn text-sm" onclick={() => openInstance(sheet.id, node.id)}>Open to fill its slots</button>
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
        kind={catalogKind(resources(sheet, app.snapshot?.sheets ?? []).nodes.get(node.id))}
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
      <!-- Started over when a line is added or removed, as caps are (no ids to keep an open picker on its line). -->
      {#key s.lines.length}
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
      {/key}
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
