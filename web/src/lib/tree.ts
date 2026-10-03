// The sheet tree as the snapshot gives it: the root sheet holds the others as sheet nodes, which hold theirs. A
// sheet's parent is wherever its sheet node is; blueprints stand apart (placed by copy).

import type { GraphNode, Sheet, Snapshot } from "./types";

export function rootSheet(snapshot: Snapshot): Sheet | undefined {
  return snapshot.sheets.find((s) => s.id === snapshot.root) ?? snapshot.sheets.find((s) => s.root);
}

/** The sheets directly inside {@code sheetId}, by name. */
export function childSheets(snapshot: Snapshot, sheetId: string): Sheet[] {
  return snapshot.sheets.filter((s) => s.parent === sheetId).sort((a, b) => a.name.localeCompare(b.name));
}

export function parentSheet(snapshot: Snapshot, sheet: Sheet): Sheet | undefined {
  return sheet.parent ? snapshot.sheets.find((s) => s.id === sheet.parent) : undefined;
}

/** The sheets above {@code sheet}, from the root down to its parent. */
export function ancestors(snapshot: Snapshot, sheet: Sheet): Sheet[] {
  const out: Sheet[] = [];
  let at = parentSheet(snapshot, sheet);
  for (let depth = 0; at && depth < 64; depth++) {
    out.unshift(at);
    at = parentSheet(snapshot, at);
  }
  return out;
}

/** Every sheet inside {@code sheet}, all the way down. */
export function descendants(snapshot: Snapshot, sheet: Sheet): Sheet[] {
  const out: Sheet[] = [];
  const todo = [sheet.id];
  while (todo.length) {
    const id = todo.shift()!;
    for (const child of snapshot.sheets.filter((s) => s.parent === id)) {
      if (!out.includes(child)) {
        out.push(child);
        todo.push(child.id);
      }
    }
  }
  return out;
}

/** "Smelting › Ores": a sheet's names from below the root down to it (the root itself, and blueprints: their name). */
export function sheetPath(snapshot: Snapshot, sheet: Sheet): string {
  if (sheet.root) return sheet.name;
  return [...ancestors(snapshot, sheet).filter((s) => !s.root), sheet].map((s) => s.name).join(" › ");
}

/** The sheet a sheet node holds. */
export function heldSheet(snapshot: Snapshot | null, node: GraphNode | undefined): Sheet | undefined {
  const s = node?.settings;
  return s?.kind === "sheet" ? snapshot?.sheets.find((x) => x.id === s.sheet) : undefined;
}

/**
 * Why a sheet isn't running: the network's switch, its own switch, a sheet above it, or a signal into its node's
 * gate.
 */
export type OffReason =
  | { kind: "network" }
  | { kind: "self" }
  | { kind: "parent"; sheet: Sheet }
  /** Held off by a signal into the gate of {@code sheet}'s node: this sheet's, or one above it. */
  | { kind: "gate"; sheet: Sheet };

export function offReason(snapshot: Snapshot, sheet: Sheet, sheetsOff: string[]): OffReason | null {
  if (sheet.blueprint) return null;
  if (!snapshot.enabled) return { kind: "network" };
  if (!sheet.root && sheet.enabled === false) return { kind: "self" };
  const above = ancestors(snapshot, sheet).find((s) => !s.root && s.enabled === false);
  if (above) return { kind: "parent", sheet: above };
  if (sheetsOff.includes(sheet.id)) {
    // The sheets inside a gated one are listed off too: the gate is on the highest one listed.
    const gated = [...ancestors(snapshot, sheet), sheet].find((s) => sheetsOff.includes(s.id)) ?? sheet;
    return { kind: "gate", sheet: gated };
  }
  return null;
}
