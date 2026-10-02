// Blueprints: reusable sheets whose device nodes are slots, placeholders for a kind of block. A copy node places one
// and says which block fills each slot (and how far the set is turned).

import type { Binding, CopySettings, Device, DeviceNodeSettings, GraphNode, Sheet, Snapshot } from "./types";
import { posKey } from "./types";

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
  if (!from || !binding) return { bindings, filled: 0 };
  const turns = binding.turns ?? 0;
  const next = { ...bindings };
  const taken = new Set(Object.values(next).map((b) => posKey(b.device)));
  let filled = 0;
  for (const slot of slots) {
    if (next[slot.id] || !slot.settings.block) continue;
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

/** A copy node's bindings (none yet: empty). */
export function bindingsOf(node: GraphNode | undefined): Record<string, Binding> {
  return node?.settings.kind === "copy" ? ((node.settings as CopySettings).bindings ?? {}) : {};
}
