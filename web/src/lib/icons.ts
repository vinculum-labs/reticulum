import type { Component } from "svelte";
import Activity from "@lucide/svelte/icons/activity";
import AppWindow from "@lucide/svelte/icons/app-window";
import Binary from "@lucide/svelte/icons/binary";
import Box from "@lucide/svelte/icons/box";
import Clock from "@lucide/svelte/icons/clock";
import Funnel from "@lucide/svelte/icons/funnel";
import Gauge from "@lucide/svelte/icons/gauge";
import Hash from "@lucide/svelte/icons/hash";
import Hourglass from "@lucide/svelte/icons/hourglass";
import Layers from "@lucide/svelte/icons/layers";
import LogIn from "@lucide/svelte/icons/log-in";
import LogOut from "@lucide/svelte/icons/log-out";
import Package from "@lucide/svelte/icons/package";
import Radio from "@lucide/svelte/icons/radio";
import RadioTower from "@lucide/svelte/icons/radio-tower";
import Scale from "@lucide/svelte/icons/scale";
import Split from "@lucide/svelte/icons/split";
import Timer from "@lucide/svelte/icons/timer";
import ToggleRight from "@lucide/svelte/icons/toggle-right";
import type { NodeKind } from "./types";

export { default as SparklesIcon } from "@lucide/svelte/icons/sparkles";
export { default as SplineIcon } from "@lucide/svelte/icons/spline";
export { default as ZapIcon } from "@lucide/svelte/icons/zap";

/** An icon for each node kind, for the toolbar and menus (Lucide, ISC license). */
export const KIND_ICON: Record<NodeKind, Component<{ size?: number | string; class?: string }>> = {
  device: Box,
  filter: Funnel,
  rate_limit: Hourglass,
  splitter: Split,
  kit: Package,
  sheet: AppWindow,
  subsheet: Layers,
  sheet_input: LogIn,
  sheet_output: LogOut,
  level: Gauge,
  compare: Scale,
  logic: Binary,
  timer: Timer,
  pulse: Activity,
  latch: ToggleRight,
  counter: Hash,
  delay: Clock,
  transmit: RadioTower,
  receive: Radio,
};
