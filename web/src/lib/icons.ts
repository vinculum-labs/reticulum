import type { Component } from "svelte";
import Activity from "@lucide/svelte/icons/activity";
import AppWindow from "@lucide/svelte/icons/app-window";
import Binary from "@lucide/svelte/icons/binary";
import Box from "@lucide/svelte/icons/box";
import Clock from "@lucide/svelte/icons/clock";
import Siren from "@lucide/svelte/icons/siren";
import AudioLines from "@lucide/svelte/icons/audio-lines";
import Monitor from "@lucide/svelte/icons/monitor";
import Power from "@lucide/svelte/icons/power";
import Funnel from "@lucide/svelte/icons/funnel";
import Gauge from "@lucide/svelte/icons/gauge";
import Hash from "@lucide/svelte/icons/hash";
import Hourglass from "@lucide/svelte/icons/hourglass";
import Stamp from "@lucide/svelte/icons/stamp";
import LogIn from "@lucide/svelte/icons/log-in";
import LogOut from "@lucide/svelte/icons/log-out";
import Package from "@lucide/svelte/icons/package";
import Scale from "@lucide/svelte/icons/scale";
import Split from "@lucide/svelte/icons/split";
import Timer from "@lucide/svelte/icons/timer";
import CircleDot from "@lucide/svelte/icons/circle-dot";
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
  instance: Stamp,
  sheet_input: LogIn,
  sheet_output: LogOut,
  level: Gauge,
  compare: Scale,
  logic: Binary,
  constant: CircleDot,
  timer: Timer,
  pulse: Activity,
  latch: ToggleRight,
  counter: Hash,
  delay: Clock,
  alarm: Siren,
  sequencer: AudioLines,
  switch: Power,
  display: Monitor,
};
