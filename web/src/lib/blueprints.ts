// Blueprints: reusable sheets whose device nodes are slots, placeholders for a kind of block. An instance node places one
// and says which block fills each slot (and how far the set is turned).

import type { Binding, InstanceSettings, Device, DeviceNodeSettings, GlobalPos, GraphNode, Sheet, Snapshot } from "./types";
import { posKey } from "./types";
import type { Activity } from "./graph";
import { nameUuid } from "./nameUuid";

/** Every instance of a blueprint in the network: the sheet it is on and its node. */
export function instancesOf(snapshot: Snapshot, blueprintId: string): { sheet: Sheet; node: GraphNode }[] {
  const out: { sheet: Sheet; node: GraphNode }[] = [];
  for (const sheet of snapshot.sheets) {
    for (const node of sheet.nodes ?? []) {
      if (node.settings.kind === "instance" && node.settings.sheet === blueprintId) out.push({ sheet, node });
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
 * filling instances by layout; this one has none to measure from). Spread out so two such slots never share one.
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
 * A blueprint's slots as a placement fills them, with where each was laid out: its own slots (where the block they
 * were made from stood), and those of blueprints placed in it by the layout those keep (the blocks they had when the
 * blueprint was made from a sheet). A slot with no place (picked from the catalog, or in a blueprint placed by hand)
 * fills only by hand.
 */
export function layoutSlots(blueprint: Sheet, sheets: Sheet[], depth = 0, path = new Set<string>()): { key: string; block?: string; laid?: GlobalPos }[] {
  if (depth >= 8 || path.has(blueprint.id)) return [];
  path.add(blueprint.id);
  const out: { key: string; block?: string; laid?: GlobalPos }[] = [];
  for (const n of blueprint.nodes ?? []) {
    const s = n.settings;
    if (s.kind === "device") out.push({ key: n.id, block: s.block, laid: placeless(s.device) ? undefined : s.device });
    else if (s.kind === "instance") {
      const inner = sheets.find((x) => x.id === s.sheet && x.blueprint);
      if (!inner) continue;
      for (const slot of layoutSlots(inner, sheets, depth + 1, path)) {
        out.push({ key: inInstance(n.id, slot.key), block: slot.block, laid: s.layout?.[slot.key]?.device });
      }
    }
  }
  path.delete(blueprint.id);
  return out;
}

/**
 * Fills an instance's empty slots by layout, from one filled slot (the anchor): each other slot's block is looked for at
 * the same place relative to the anchor's block as in the blueprint, turned the same way, and taken only if it is
 * exactly that kind of block and lives nowhere yet. Slots of blueprints placed in it count too, where it keeps their
 * layout. Returns the new bindings and how many slots it filled.
 */
export function fillByLayout(
  blueprint: Sheet,
  bindings: Record<string, Binding>,
  anchor: string,
  devices: Device[],
  placed: (device: Device) => boolean,
  sheets: Sheet[] = [],
): { bindings: Record<string, Binding>; filled: number } {
  const slots = layoutSlots(blueprint, sheets);
  const from = slots.find((s) => s.key === anchor);
  const binding = bindings[anchor];
  // A slot with no place in the layout has nothing to measure from.
  if (!from?.laid || !binding) return { bindings, filled: 0 };
  const origin = from.laid.pos;
  const turns = binding.turns ?? 0;
  const next = { ...bindings };
  const taken = new Set(Object.values(next).map((b) => posKey(b.device)));
  let filled = 0;
  for (const slot of slots) {
    if (next[slot.key] || !slot.block || !slot.laid) continue;
    const [dx, dz] = turn(slot.laid.pos[0] - origin[0], slot.laid.pos[2] - origin[2], turns);
    const dy = slot.laid.pos[1] - origin[1];
    const at = binding.device.pos;
    const want = { dimension: binding.device.dimension, pos: [at[0] + dx, at[1] + dy, at[2] + dz] as [number, number, number] };
    const found = devices.find((d) => posKey(d.pos) === posKey(want) && d.state === "active");
    if (!found || found.block !== slot.block || taken.has(posKey(found.pos)) || placed(found)) continue;
    next[slot.key] = { device: found.pos, ...(turns ? { turns } : {}) };
    taken.add(posKey(found.pos));
    filled++;
  }
  return { bindings: next, filled };
}

/**
 * One slot of an instance filled with {@code binding}. When it is the instance's first, the other slots are filled by layout
 * from it too (see {@link fillByLayout}). Returns the new bindings and how many more slots that filled.
 */
export function fillOne(
  blueprint: Sheet,
  bindings: Record<string, Binding>,
  slot: string,
  binding: Binding,
  devices: Device[],
  placed: (device: Device) => boolean,
  sheets: Sheet[] = [],
): { bindings: Record<string, Binding>; more: number } {
  const first = layoutSlots(blueprint, sheets).every((s) => s.key === slot || !bindings[s.key]);
  const next = { ...bindings, [slot]: binding };
  if (!first) return { bindings: next, more: 0 };
  const result = fillByLayout(blueprint, next, slot, devices, placed, sheets);
  return { bindings: result.bindings, more: result.filled };
}

/**
 * A new instance's slots filled with the blocks the blueprint was made from, each where it still is, still that kind of
 * block and living nowhere: an instance put back where it was comes back filled.
 */
export function ownBlocks(blueprint: Sheet, devices: Device[], placed: (device: Device) => boolean, sheets: Sheet[] = []): Record<string, Binding> {
  const bindings: Record<string, Binding> = {};
  // A block fills one slot (two slots made from the same device: the first gets it). Those of blueprints placed in it
  // too, where it keeps their layout.
  const taken = new Set<string>();
  for (const slot of layoutSlots(blueprint, sheets)) {
    const laid = slot.laid;
    const found = slot.block && laid ? devices.find((d) => posKey(d.pos) === posKey(laid) && d.state === "active") : undefined;
    if (found && found.block === slot.block && !placed(found) && !taken.has(posKey(found.pos))) {
      bindings[slot.key] = { device: found.pos };
      taken.add(posKey(found.pos));
    }
  }
  return bindings;
}

/** The server's id for a blueprint's node or wire as it runs in an instance (its activity is kept under it). */
export function inInstance(instanceNode: string, id: string): string {
  return nameUuid(`${instanceNode}/${id}`);
}

/**
 * Every slot a placement of this blueprint fills, by the key its bindings use (as the server keys them): its own slots
 * by id, and those of blueprints placed in it (any depth) by the placed node and the key there ({@link inInstance}).
 */
export function allSlotKeys(blueprint: Sheet | undefined, sheets: Sheet[], depth = 0, path = new Set<string>()): string[] {
  if (!blueprint || depth >= 8 || path.has(blueprint.id)) return [];
  path.add(blueprint.id);
  const out: string[] = [];
  for (const n of blueprint.nodes ?? []) {
    if (n.settings.kind === "device") out.push(n.id);
    else if (n.settings.kind === "instance") {
      const inner = sheets.find((s) => s.id === (n.settings as InstanceSettings).sheet && s.blueprint);
      for (const key of allSlotKeys(inner, sheets, depth + 1, path)) out.push(inInstance(n.id, key));
    }
  }
  path.delete(blueprint.id);
  return out;
}

/**
 * Activity as an open instance's nodes and wires show it. The canvas shows the blueprint's own nodes, but the server names
 * them in this instance "<instance node>/<blueprint node>" (as a name UUID), and keeps their activity under those names.
 * Statuses aren't moved: the server puts those on the instance node.
 */
export function instanceActivity(activity: Activity, instanceNode: string, blueprint: Sheet, sheets: Sheet[] = []): Activity {
  const ids = [...(blueprint.nodes ?? []).map((n) => n.id), ...(blueprint.wires ?? []).map((w) => w.id)];
  // The instance's name for each blueprint id. A pin's value is under "<id>/out/<n>" or "<id>/in/<n>": moved with it.
  const own = new Map(ids.map((id) => [inInstance(instanceNode, id), id]));
  // A blueprint placed in this one: its pins' values, by the name the canvas looks them up under (the inner placed
  // blueprint's id here, then the pin's).
  for (const n of blueprint.nodes ?? []) {
    const s = n.settings;
    if (s.kind !== "instance") continue;
    const inner = sheets.find((x) => x.id === s.sheet);
    for (const pin of inner?.nodes ?? []) {
      if (pin.settings.kind === "sheet_input" || pin.settings.kind === "sheet_output") {
        own.set(inInstance(inInstance(instanceNode, n.id), pin.id), inInstance(n.id, pin.id));
      }
    }
  }
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
 * open instance, under the instance's name for it.
 */
export function liveKey(key: string, instanceNode: string | undefined): string {
  if (!instanceNode) return key;
  const slash = key.indexOf("/");
  return slash < 0 ? inInstance(instanceNode, key) : inInstance(instanceNode, key.slice(0, slash)) + key.slice(slash);
}

/** An instance node's bindings (none yet: empty). */
export function bindingsOf(node: GraphNode | undefined): Record<string, Binding> {
  return node?.settings.kind === "instance" ? ((node.settings as InstanceSettings).bindings ?? {}) : {};
}
