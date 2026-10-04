// Charting a device or a resource from wherever it is offered (the Charts view, a device's right-click menu).

import { app, edit } from "./state.svelte";
import { RESOURCE_LABEL } from "./kinds";
import { posKey, type ChartSeries, type Device, type Resource } from "./types";

/** What a device can be charted for: what it holds, or what moves into and out of it. */
export type DeviceChart = "level" | "through";

/** The resources a device has that can be charted (energy, items, fluid, chemical). */
export function chartableResources(device: Device): Resource[] {
  return (["energy", "item", "fluid", "chemical"] as Resource[]).filter((r) => device.resources?.[r]?.length);
}

/** The chart of a device for a resource, if it is charted. */
export function deviceChart(device: Device, kind: DeviceChart, resource: Resource): ChartSeries | undefined {
  return (app.snapshot?.charts ?? []).find(
    (c) => c.kind === kind && c.resource === resource && !!c.device && posKey(c.device) === posKey(device.pos),
  );
}

/** Charts something. */
export function addChart(chart: Partial<ChartSeries>, label = "chart") {
  // Not undoable from here: its key (what removing it needs) is the server's to work out.
  edit([{ type: "addChart", chart }], undefined, label);
}

/** Stops charting something (undo charts it again). */
export function removeChart(c: ChartSeries) {
  const chart = { kind: c.kind, id: c.id, device: c.device, resource: c.resource };
  edit([{ type: "removeChart", key: c.key }], [{ type: "addChart", chart }], "stop charting");
}

/** Charts a device for a resource, or stops if it already is. */
export function toggleDeviceChart(device: Device, kind: DeviceChart, resource: Resource) {
  const existing = deviceChart(device, kind, resource);
  if (existing) removeChart(existing);
  else addChart({ kind, device: device.pos, resource });
}

export function deviceChartLabel(kind: DeviceChart, resource: Resource): string {
  const what = RESOURCE_LABEL[resource].toLowerCase();
  return kind === "level" ? `What it holds — ${what}` : `What moves in and out — ${what}`;
}
