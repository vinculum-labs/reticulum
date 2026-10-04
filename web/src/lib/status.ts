/** How the network, and each sheet in it, is doing: an icon, its color and what it means (top bar and sheet tree). */
import NetworkIcon from "@lucide/svelte/icons/network";
import Play from "@lucide/svelte/icons/circle-play";
import Stop from "@lucide/svelte/icons/circle-stop";
import TriangleAlert from "@lucide/svelte/icons/triangle-alert";
import Unplug from "@lucide/svelte/icons/unplug";
import Zap from "@lucide/svelte/icons/zap";
import ZapOff from "@lucide/svelte/icons/zap-off";
import type { Component } from "svelte";
import { KIND_ICON } from "./icons";
import type { OffReason } from "./tree";
import type { GraphNode, Sheet } from "./types";

export interface Status {
  icon: Component<{ size?: number | string; class?: string }>;
  color: string;
  title: string;
}

export function networkStatus(running: boolean, enabled: boolean, unpowered: boolean): Status {
  if (running && unpowered) {
    return { icon: ZapOff, color: "var(--bad)", title: "No power: the controller has too little FE for this network, so only energy flows run" };
  }
  if (running) return { icon: Play, color: "var(--good)", title: "Running" };
  if (enabled) return { icon: Unplug, color: "var(--warn)", title: "No controller loaded: the controller's chunk isn't loaded, so nothing runs" };
  return { icon: Stop, color: "var(--muted)", title: "Off: switched off with the On checkbox" };
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
export function treeStatus(sheet: Sheet, off: OffReason | null, running: boolean, enabled: boolean, unpowered: boolean): TreeStatus {
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
    badges.push({ icon: unpowered ? ZapOff : Unplug, color: unpowered ? "var(--bad)" : "var(--warn)", corner: "bottom" });
    lines.push(
      unpowered
        ? "No power: the controller has too little FE for this network, so only energy flows run"
        : "Not running: the controller's chunk isn't loaded",
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
  if (sheet.errors?.length) {
    badges.push({ icon: TriangleAlert, color: "var(--bad)", corner: "top" });
    lines.push(...sheet.errors.map((e) => e.message));
  }
  return { icon: sheet.root ? NetworkIcon : KIND_ICON.sheet, color, badges, title: lines.join("\n") };
}

/**
 * A blueprint instance in the tree, read like a sheet: off by its node's switch or the sheet holding it, with the problems
 * the server put on its node.
 */
export function instanceStatus(node: GraphNode, holder: Sheet, holderOff: OffReason | null, running: boolean, enabled: boolean, unpowered: boolean): TreeStatus {
  const off: OffReason | null =
    node.enabled === false ? { kind: "self" }
      : !holderOff ? null
      : holderOff.kind === "network" || holderOff.kind === "parent" || holderOff.kind === "controller" ? holderOff
      : { kind: "parent", sheet: holder };
  const errors = (holder.errors ?? []).filter((e) => e.node === node.id);
  return { ...treeStatus({ ...holder, root: false, errors }, off, running, enabled, unpowered), icon: KIND_ICON.instance };
}
