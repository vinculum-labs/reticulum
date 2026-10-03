// Blueprints: reusable sheets whose device nodes are slots, placeholders for a kind of block. A copy node places one
// and says which block fills each slot (and how far the set is turned).

import type { Binding, CopySettings, Device, DeviceNodeSettings, GlobalPos, GraphNode, Sheet, Snapshot } from "./types";
import { posKey } from "./types";
import type { Activity } from "./graph";
import { nameUuid } from "./nameUuid";

/** Every copy of a blueprint in the network: the sheet it is on and its node. */
export function copiesOf(snapshot: Snapshot, blueprintId: string): { sheet: Sheet; node: GraphNode }[] {
  const out: { sheet: Sheet; node: GraphNode }[] = [];
  for (const sheet of snapshot.sheets) {
    for (const node of sheet.nodes ?? []) {
      if (node.settings.kind === "copy" && node.settings.sheet === blueprintId) out.push({ sheet, node });
    }
  }
  return out;
}

/** A blueprint's slots: its device nodes. */
export function slotsOf(blueprint: Sheet | undefined): (GraphNode & { settings: DeviceNodeSettings })[] {
  return (blueprint?.nodes ?? []).filter((n): n is GraphNode & { settings: DeviceNodeSettings } => n.settings.kind === "device");
}

/** "Furnace" for "minecraft:furnace": a block id as words. */
export function blockName(block: string | undefined): string {
  if (!block) return "Any block";
  const path = block.includes(":") ? block.slice(block.indexOf(":") + 1) : block;
  const words = path.replace(/_/g, " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/** How far below any world a slot picked from the catalog "sits": it names a kind of block, made from no block. */
const NOWHERE_Y = -100_000;

/**
 * A place for a slot picked from the catalog: below any world, so no block is ever there (a slot keeps a place, for
 * filling copies by layout; this one has none to measure from). Spread out so two such slots never share one.
 */
export function nowhere(): GlobalPos {
  return { dimension: "minecraft:overworld", pos: [Math.floor(Math.random() * 1_000_000), NOWHERE_Y, Math.floor(Math.random() * 1_000_000)] };
}

/** Whether a slot was picked from the catalog (it has no block it was made from, so no place in a layout). */
export function placeless(pos: GlobalPos): boolean {
  return pos.pos[1] <= NOWHERE_Y;
}

/** How a slot is named: its label, else its kind of block. */
export function slotName(slot: GraphNode): string {
  if (slot.label) return slot.label;
  return `${blockName(slot.settings.kind === "device" ? slot.settings.block : undefined)} slot`;
}

/** A position offset turned {@code turns} quarter turns clockwise seen from above (north turns to east). */
function turn(dx: number, dz: number, turns: number): [number, number] {
  let x = dx;
  let z = dz;
  for (let i = 0; i < ((turns % 4) + 4) % 4; i++) [x, z] = [-z, x];
  return [x, z];
}

/**
 * Fills a copy's empty slots by layout, from one filled slot (the anchor): each other slot's block is looked for at
 * the same place relative to the anchor's block as in the blueprint, turned the same way, and taken only if it is
 * exactly that kind of block and lives nowhere yet. Returns the new bindings and how many slots it filled.
 */
export function fillByLayout(
  blueprint: Sheet,
  bindings: Record<string, Binding>,
  anchor: string,
  devices: Device[],
  placed: (device: Device) => boolean,
): { bindings: Record<string, Binding>; filled: number } {
  const slots = slotsOf(blueprint);
  const from = slots.find((s) => s.id === anchor);
  const binding = bindings[anchor];
  // A slot picked from the catalog has no place to measure a layout from, nor to be found at.
  if (!from || !binding || placeless(from.settings.device)) return { bindings, filled: 0 };
  const turns = binding.turns ?? 0;
  const next = { ...bindings };
  const taken = new Set(Object.values(next).map((b) => posKey(b.device)));
  let filled = 0;
  for (const slot of slots) {
    if (next[slot.id] || !slot.settings.block || placeless(slot.settings.device)) continue;
    const [dx, dz] = turn(slot.settings.device.pos[0] - from.settings.device.pos[0], slot.settings.device.pos[2] - from.settings.device.pos[2], turns);
    const dy = slot.settings.device.pos[1] - from.settings.device.pos[1];
    const at = binding.device.pos;
    const want = { dimension: binding.device.dimension, pos: [at[0] + dx, at[1] + dy, at[2] + dz] as [number, number, number] };
    const found = devices.find((d) => posKey(d.pos) === posKey(want) && d.state === "active");
    if (!found || found.block !== slot.settings.block || taken.has(posKey(found.pos)) || placed(found)) continue;
    next[slot.id] = { device: found.pos, ...(turns ? { turns } : {}) };
    taken.add(posKey(found.pos));
    filled++;
  }
  return { bindings: next, filled };
}

/**
 * One slot of a copy filled with {@code binding}. When it is the copy's first, the other slots are filled by layout
 * from it too (see {@link fillByLayout}). Returns the new bindings and how many more slots that filled.
 */
export function fillOne(
  blueprint: Sheet,
  bindings: Record<string, Binding>,
  slot: string,
  binding: Binding,
  devices: Device[],
  placed: (device: Device) => boolean,
): { bindings: Record<string, Binding>; more: number } {
  const first = slotsOf(blueprint).every((s) => s.id === slot || !bindings[s.id]);
  const next = { ...bindings, [slot]: binding };
  if (!first) return { bindings: next, more: 0 };
  const result = fillByLayout(blueprint, next, slot, devices, placed);
  return { bindings: result.bindings, more: result.filled };
}

/**
 * A new copy's slots filled with the blocks the blueprint was made from, each where it still is, still that kind of
 * block and living nowhere: a copy put back where it was comes back filled.
 */
export function ownBlocks(blueprint: Sheet, devices: Device[], placed: (device: Device) => boolean): Record<string, Binding> {
  const bindings: Record<string, Binding> = {};
  // A block fills one slot (two slots made from the same device: the first gets it).
  const taken = new Set<string>();
  for (const slot of slotsOf(blueprint)) {
    const block = slot.settings.block;
    const found = block ? devices.find((d) => posKey(d.pos) === posKey(slot.settings.device) && d.state === "active") : undefined;
    if (found && found.block === block && !placed(found) && !taken.has(posKey(found.pos))) {
      bindings[slot.id] = { device: found.pos };
      taken.add(posKey(found.pos));
    }
  }
  return bindings;
}

/** The server's id for a blueprint's node or wire as it runs in a copy (its activity is kept under it). */
export function inCopy(copyNode: string, id: string): string {
  return nameUuid(`${copyNode}/${id}`);
}

/**
 * Activity as an open copy's nodes and wires show it. The canvas shows the blueprint's own nodes, but the server names
 * them in this copy "<copy node>/<blueprint node>" (as a name UUID), and keeps their activity under those names.
 * Statuses aren't moved: the server puts those on the copy node.
 */
// XXX: a copy inside a blueprint runs once per copy of that blueprint, under inCopy(inCopy(outer, nested), inner);
// opened on its own (from the blueprint's Copies) its activity is looked up as inCopy(nested, inner) and never found.
export function copyActivity(activity: Activity, copyNode: string, blueprint: Sheet): Activity {
  const ids = [...(blueprint.nodes ?? []).map((n) => n.id), ...(blueprint.wires ?? []).map((w) => w.id)];
  // The copy's name for each blueprint id. A pin's value is under "<id>/out/<n>" or "<id>/in/<n>": moved with it.
  const own = new Map(ids.map((id) => [inCopy(copyNode, id), id]));
  function remap<T>(from: Record<string, T>): Record<string, T> {
    const out: Record<string, T> = {};
    for (const [key, value] of Object.entries(from)) {
      const slash = key.indexOf("/");
      const id = own.get(slash < 0 ? key : key.slice(0, slash));
      if (id !== undefined) out[slash < 0 ? id : id + key.slice(slash)] = value;
    }
    return out;
  }
  return { ...activity, rates: remap(activity.rates), fiveMinutes: remap(activity.fiveMinutes), signals: remap(activity.signals), me: remap(activity.me) };
}

/**
 * Where the server keeps activity for this id (a node's, or a pin's "<node>/out/<n>") as the canvas shows it: in an
 * open copy, under the copy's name for it.
 */
export function liveKey(key: string, copyNode: string | undefined): string {
  if (!copyNode) return key;
  const slash = key.indexOf("/");
  return slash < 0 ? inCopy(copyNode, key) : inCopy(copyNode, key.slice(0, slash)) + key.slice(slash);
}

/** A copy node's bindings (none yet: empty). */
export function bindingsOf(node: GraphNode | undefined): Record<string, Binding> {
  return node?.settings.kind === "copy" ? ((node.settings as CopySettings).bindings ?? {}) : {};
}
