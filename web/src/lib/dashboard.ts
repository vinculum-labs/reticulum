// The dashboard's controls: every Switch and Display that runs, on its sheet or in each blueprint instance.

import { app, pendingSignals, request } from "./state.svelte";
import { inInstance } from "./blueprints";
import { sheetPath } from "./tree";
import type { GraphNode, Sheet, Snapshot } from "./types";

/** One Switch or Display as it runs: on its sheet, or in one instance of its blueprint (under the id it runs as there). */
export interface Control {
  node: GraphNode;
  /** The sheet (or blueprint) it is on. */
  sheet: Sheet;
  /** The id it runs under: its own, or made from the instances it is in (as the server pastes them). */
  run: string;
  /** Where it is, as the dashboard shows it: a sheet's path, then each placed blueprint it is in. */
  place: string;
  /** What the dashboard groups it by: its sheet, or the placed blueprint it is in (two may share a name). */
  group: string;
}

/** How deep instances inside blueprints are followed (the engine refuses deeper ones). */
const NESTING = 8;

export function instanceName(snapshot: Snapshot, node: GraphNode): string {
  const s = node.settings;
  return node.label || (s.kind === "instance" ? snapshot.sheets.find((x) => x.id === s.sheet)?.name : undefined) || "Blueprint";
}

/** Every Switch and Display that runs, sheet by sheet (blueprints only through their instances). */
export function controls(snapshot: Snapshot): Control[] {
  const out: Control[] = [];
  const walk = (sheet: Sheet, run: (id: string) => string, place: string, group: string, depth: number) => {
    for (const node of sheet.nodes ?? []) {
      const s = node.settings;
      if (s.kind === "switch" || s.kind === "display") out.push({ node, sheet, run: run(node.id), place, group });
      if (s.kind === "instance" && depth < NESTING) {
        const blueprint = snapshot.sheets.find((x) => x.id === s.sheet && x.blueprint);
        const at = run(node.id);
        if (blueprint) walk(blueprint, (id) => inInstance(at, id), `${place} › ${instanceName(snapshot, node)}`, at, depth + 1);
      }
    }
  };
  for (const sheet of snapshot.sheets) if (!sheet.blueprint) walk(sheet, (id) => id, sheetPath(snapshot, sheet), sheet.id, 0);
  return out;
}

/** Sets a switch where it runs; shown at once, and confirmed by the next values from the game. */
export async function setSwitch(control: Pick<Control, "node" | "sheet" | "run">, value: number): Promise<void> {
  const before = app.signals[control.run];
  // Shown until the game reports it (a report from before it applied this would put the old value back meanwhile).
  const pending = { value, until: Date.now() + 3000 };
  pendingSignals.set(control.run, pending);
  app.signals = { ...app.signals, [control.run]: value };
  try {
    await request("setSwitch", { sheet: control.sheet.id, node: control.node.id, run: control.run, value });
  } catch (e) {
    // Put back only if nothing newer was set meanwhile (that one's own answer decides it).
    if (pendingSignals.get(control.run) === pending) {
      pendingSignals.delete(control.run);
      app.signals = { ...app.signals, [control.run]: before ?? 0 };
    }
    throw e;
  }
}
