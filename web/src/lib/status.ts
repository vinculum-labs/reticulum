/** How the network, and each sheet in it, is doing: an icon, its color and what it means (top bar and sheet tree). */
import Play from "@lucide/svelte/icons/circle-play";
import Stop from "@lucide/svelte/icons/circle-stop";
import TriangleAlert from "@lucide/svelte/icons/triangle-alert";
import Unplug from "@lucide/svelte/icons/unplug";
import Zap from "@lucide/svelte/icons/zap";
import ZapOff from "@lucide/svelte/icons/zap-off";
import type { Component } from "svelte";
import type { OffReason } from "./tree";
import type { Sheet } from "./types";

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

/** A sheet below the top: its problems first, then why it is off, else running (when the network runs). */
export function sheetStatus(sheet: Sheet, off: OffReason | null, network: Status): Status {
  if (sheet.errors?.length) return { icon: TriangleAlert, color: "var(--bad)", title: sheet.errors.map((e) => e.message).join("\n") };
  if (off?.kind === "gate") return { icon: Zap, color: "var(--c-signal)", title: "Off: a signal into its node's gate holds it off" };
  if (off?.kind === "self") return { icon: Stop, color: "var(--muted)", title: "Off: switched off" };
  if (off?.kind === "parent") return { icon: Stop, color: "var(--muted)", title: `Off: ${off.sheet.name}, a sheet above it, is off` };
  if (off?.kind === "network") return { icon: Stop, color: "var(--muted)", title: "Off: the network is off" };
  return network.icon === Play ? network : { ...network, title: `Not running: ${network.title}` };
}
