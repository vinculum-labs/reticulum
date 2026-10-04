/** How the network, and each sheet in it, is doing: an icon, its color and what it means (top bar and sheet tree). */
import NetworkIcon from "@lucide/svelte/icons/network";
import Play from "@lucide/svelte/icons/circle-play";
import Stop from "@lucide/svelte/icons/circle-stop";
import TriangleAlert from "@lucide/svelte/icons/triangle-alert";
import Unplug from "@lucide/svelte/icons/unplug";
import Ban from "@lucide/svelte/icons/ban";
import Zap from "@lucide/svelte/icons/zap";
import ZapOff from "@lucide/svelte/icons/zap-off";
import type { Component } from "svelte";
import { KIND_ICON } from "./icons";
import type { OffReason } from "./tree";
import { posKey, type Device, type GlobalPos, type GraphNode, type Sheet, type Snapshot } from "./types";

export interface Status {
  icon: Component<{ size?: number | string; class?: string }>;
  color: string;
  title: string;
}

/**
 * {@code hasController}: whether the network has a controller at all; without one it can't run, as with one whose
 * chunk isn't loaded, but the fix differs (place one, or load its chunk).
 */
export function networkStatus(running: boolean, enabled: boolean, unpowered: boolean, hasController = true): Status {
  if (running && unpowered) {
    return { icon: ZapOff, color: "var(--bad)", title: "No power: the controller has too little FE for this network, so only energy flows run" };
  }
  if (running) return { icon: Play, color: "var(--good)", title: "Running" };
  if (enabled && !hasController) return { icon: Ban, color: "var(--warn)", title: "No controller: this network has none, so nothing runs — place one" };
  if (enabled) return { icon: Unplug, color: "var(--warn)", title: "Controller not loaded: its chunk isn't loaded, so nothing runs" };
  return { icon: Stop, color: "var(--muted)", title: "Off: switched off with the On checkbox" };
}

/** A network's tab: its dot's color and what it means — problems first, then offline devices, then whether it runs. */
export function tabStatus(n: { running: boolean; enabled: boolean; errors?: number; offline?: number }): { color: string; title: string } {
  const lines: string[] = [];
  if (n.errors) lines.push(`${n.errors} problem${n.errors === 1 ? "" : "s"} keeping flows from running`);
  if (n.offline) lines.push(`${n.offline} device${n.offline === 1 ? "" : "s"} offline — out of range, orphaned or missing`);
  lines.push(n.running ? "Running" : n.enabled ? "Not running" : "Off");
  const color = n.errors ? "var(--bad)" : n.offline || (n.enabled && !n.running) ? "var(--warn)" : n.running ? "var(--good)" : "var(--muted)";
  return { color, title: lines.join("\n") };
}

/** Why a device does nothing right now, or null when it is fine. */
export function offlineReason(device: Device): string | null {
  if (device.state === "orphaned") return "orphaned (no connector reaches it)";
  if (device.state === "missing") return "missing (the block is gone)";
  if (device.outOfRange) return "out of range (its connector can't reach the controller)";
  return null;
}

/**
 * The devices a sheet's own nodes use — and its placed blueprints fill slots with — that do nothing right now, one line
 * each. {@code only}: just that node's. Blueprints have none: their devices are only layout positions.
 */
export function offlineUsed(snapshot: Snapshot, sheet: Sheet, only?: string): string[] {
  if (sheet.blueprint) return [];
  const byKey = new Map(snapshot.devices.map((d) => [posKey(d.pos), d]));
  const lines = new Map<string, string>();
  const note = (pos: GlobalPos) => {
    const device = byKey.get(posKey(pos));
    const why = device && offlineReason(device);
    if (device && why) lines.set(posKey(pos), `${device.name || device.defaultName} is ${why}`);
  };
  for (const node of sheet.nodes ?? []) {
    if (only && node.id !== only) continue;
    const s = node.settings;
    if ((s.kind === "device" || s.kind === "level") && s.device) note(s.device);
    if (s.kind === "instance") Object.values(s.bindings ?? {}).forEach((b) => note(b.device));
  }
  return [...lines.values()];
}

/** Problems and offline devices in the sheets inside this one (not its own), so a collapsed row still shows them. */
export function issuesInside(snapshot: Snapshot, sheet: Sheet): { errors: number; offline: number } {
  let errors = 0;
  let offline = 0;
  const todo = snapshot.sheets.filter((x) => x.parent === sheet.id);
  const seen = new Set<string>([sheet.id]);
  while (todo.length) {
    const at = todo.shift()!;
    if (seen.has(at.id)) continue;
    seen.add(at.id);
    errors += at.errors?.length ?? 0;
    offline += offlineUsed(snapshot, at).length;
    todo.push(...snapshot.sheets.filter((x) => x.parent === at.id));
  }
  return { errors, offline };
}

/** A row of the sheet tree: what it is (its icon), colored by whether it runs, badged with what else to know. */
export interface TreeStatus {
  icon: Component<{ size?: number | string; class?: string }>;
  color: string;
  /** Small marks on the icon's corners: held off by its gate (bottom), problems (top). */
  badges: { icon: Component<{ size?: number | string; class?: string }>; color: string; corner: "top" | "bottom" }[];
  title: string;
}

/**
 * The network (top row) or a sheet: plain when running, gray when off, amber when the network can't run (no power, or
 * its controller isn't loaded). The gate holding a sheet off and problems are badges, so they show whatever its state.
 */
export function treeStatus(
  sheet: Sheet,
  off: OffReason | null,
  running: boolean,
  enabled: boolean,
  unpowered: boolean,
  hasController = true,
  offline: string[] = [],
  inside: { errors: number; offline: number } = { errors: 0, offline: 0 },
): TreeStatus {
  const badges: TreeStatus["badges"] = [];
  const lines: string[] = [];
  let color = "var(--ink)";
  if (!enabled || (off && off.kind !== "gate" && off.kind !== "controller")) {
    color = "var(--muted)";
    lines.push(
      !enabled || off?.kind === "network" ? "Off: the network is off"
        : off?.kind === "self" ? "Off: switched off"
        : off?.kind === "parent" ? `Off: ${off.sheet.name}, a sheet above it, is off`
        : "Off",
    );
  } else if (!running || unpowered) {
    color = "var(--warn)";
    badges.push({ icon: unpowered ? ZapOff : hasController ? Unplug : Ban, color: unpowered ? "var(--bad)" : "var(--warn)", corner: "bottom" });
    lines.push(
      unpowered
        ? "No power: the controller has too little FE for this network, so only energy flows run"
        : hasController
          ? "Not running: the controller's chunk isn't loaded"
          : "Not running: the network has no controller",
    );
  } else if (off?.kind === "controller") {
    color = "var(--muted)";
    badges.push({ icon: Zap, color: "var(--c-signal)", corner: "bottom" });
    lines.push("Off: redstone at the controller has stopped the network");
  } else if (off?.kind === "gate") {
    color = "var(--muted)";
    badges.push({ icon: Zap, color: "var(--c-signal)", corner: "bottom" });
    lines.push(off.sheet.id === sheet.id ? "Off: a signal into its node's gate holds it off" : `Off: a signal into the gate of “${off.sheet.name}” above it holds it off`);
  } else {
    lines.push("Running");
  }
  // One mark for everything wrong in or under it: red for problems, amber when it's only devices offline.
  if (sheet.errors?.length || inside.errors) {
    badges.push({ icon: TriangleAlert, color: "var(--bad)", corner: "top" });
  } else if (offline.length || inside.offline) {
    badges.push({ icon: TriangleAlert, color: "var(--warn)", corner: "top" });
  }
  lines.push(...(sheet.errors ?? []).map((e) => e.message), ...offline);
  if (inside.errors) lines.push(`${inside.errors} problem${inside.errors === 1 ? "" : "s"} in sheets inside`);
  if (inside.offline) lines.push(`${inside.offline} device${inside.offline === 1 ? "" : "s"} offline in sheets inside`);
  return { icon: sheet.root ? NetworkIcon : KIND_ICON.sheet, color, badges, title: lines.join("\n") };
}

/**
 * A blueprint instance in the tree, read like a sheet: off by its node's switch or the sheet holding it, with the problems
 * the server put on its node.
 */
export function instanceStatus(
  node: GraphNode,
  holder: Sheet,
  holderOff: OffReason | null,
  running: boolean,
  enabled: boolean,
  unpowered: boolean,
  hasController = true,
  offline: string[] = [],
): TreeStatus {
  const off: OffReason | null =
    node.enabled === false ? { kind: "self" }
      : !holderOff ? null
      : holderOff.kind === "network" || holderOff.kind === "parent" || holderOff.kind === "controller" ? holderOff
      : { kind: "parent", sheet: holder };
  const errors = (holder.errors ?? []).filter((e) => e.node === node.id);
  return { ...treeStatus({ ...holder, root: false, errors }, off, running, enabled, unpowered, hasController, offline), icon: KIND_ICON.instance };
}
