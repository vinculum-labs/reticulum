<script lang="ts">
  import Segmented from "./controls/Segmented.svelte";
  import LayoutDashboard from "@lucide/svelte/icons/layout-dashboard";
  import ChartLine from "@lucide/svelte/icons/chart-line";
  import Lightbulb from "@lucide/svelte/icons/lightbulb";
  import Metronome from "@lucide/svelte/icons/metronome";
  import Timer from "@lucide/svelte/icons/timer";
  import Redo from "@lucide/svelte/icons/redo-2";
  import Settings from "@lucide/svelte/icons/settings";
  import Trash from "@lucide/svelte/icons/trash";
  import Undo from "@lucide/svelte/icons/undo-2";
  import {
    app,
    selectNetwork,
    undo,
    redo,
    request,
    loadNetworks,
    toast,
    setRateUnit,
  } from "../lib/state.svelte";
  import { confirmDialog } from "../lib/dialog.svelte";
  import { severityColor, tabStatus } from "../lib/status";

  const snapshot = $derived(app.snapshot);
  const alarms = $derived(app.networks.find((n) => n.id === app.current)?.alarms ?? []);

  async function deleteNetwork() {
    if (!snapshot) return;
    const id = snapshot.id;
    const ok = await confirmDialog({
      title: `Delete the network “${snapshot.name}”?`,
      message: "Its sheets, names and settings are gone for good.",
      confirm: "Delete network",
      danger: true,
    });
    if (!ok) return;
    try {
      await request("deleteNetwork", { network: id });
      if (app.current === id) {
        app.current = null;
        app.snapshot = null;
      }
      await loadNetworks(null);
    } catch (e) {
      toast((e as Error).message);
    }
  }

  /** The network's state as an icon, a color and a tooltip. */
  /** How long the saving dot stays once lit, so a quick save shows as a calm blip instead of a flicker. */
  const SAVING_MIN_MS = 1000;
  let saving = $state(false);
  let savingSince = 0;
  let savingTimer: ReturnType<typeof setTimeout> | undefined;
  $effect(() => {
    if (app.busy > 0) {
      clearTimeout(savingTimer);
      if (!saving) savingSince = Date.now();
      saving = true;
    } else if (saving) {
      clearTimeout(savingTimer);
      savingTimer = setTimeout(() => (saving = false), Math.max(0, savingSince + SAVING_MIN_MS - Date.now()));
    }
  });
</script>

<header class="flex items-center gap-3 border-b border-line bg-panel px-3 py-2">
  <span class="font-semibold tracking-wide">Reticulum</span>
  <nav class="flex min-w-0 flex-1 gap-1 overflow-x-auto" aria-label="Networks">
    {#each app.networks as n (n.id)}
      <button
        class="btn flex items-center gap-2"
        aria-current={n.id === app.current ? "page" : undefined}
        aria-pressed={n.id === app.current}
        onclick={() => n.id !== app.current && selectNetwork(n.id)}
        title={`${tabStatus(n).title}\n${n.access}, owner ${n.owner || "none"}, your role: ${n.role}`}
      >
        <span class="inline-block h-2 w-2 rounded-full" style="background: {tabStatus(n).color}"></span>
        {n.name}
      </button>
    {/each}
  </nav>
  {#if snapshot}
    <button
      class="btn flex items-center gap-1"
      aria-pressed={app.panel === "dashboard"}
      onclick={() => (app.panel = app.panel === "dashboard" ? null : "dashboard")}
      title="Alarms, busiest and waiting flows, what is switched off, tick cost and the overflow buffer"><LayoutDashboard size={14} />Dashboard{#if alarms.length}<span
          class="tabular-nums font-medium"
          style="color: {severityColor(alarms[0].severity)}"
          title={`${alarms.length} alarm${alarms.length === 1 ? "" : "s"} going off`}>{alarms.length}</span
        >{/if}</button
    >
    <button
      class="btn flex items-center gap-1"
      aria-pressed={app.panel === "charts"}
      onclick={() => (app.panel = app.panel === "charts" ? null : "charts")}
      title="What the network moves of each resource over time, and anything charted"><ChartLine size={14} />Charts</button
    >
    <button
      class="btn flex items-center gap-1"
      aria-pressed={app.panel === "identified"}
      onclick={() => (app.panel = app.panel === "identified" ? null : "identified")}
      title="What is lit up in the world"><Lightbulb size={14} />Identified{app.snapshot?.identified?.length ? ` (${app.snapshot.identified.length})` : ""}</button
    >
    <button
      class="btn flex items-center gap-1"
      aria-pressed={app.panel === "network"}
      onclick={() => (app.panel = app.panel === "network" ? null : "network")}
      title="Name, access, password and members"><Settings size={14} />Settings</button
    >
    <Segmented
      label="Rates per"
      value={app.rateUnit}
      options={[
        { value: "t", label: "", icon: Metronome, title: "Rates per tick" },
        { value: "s", label: "", icon: Timer, title: "Rates per second" },
      ]}
      onchange={setRateUnit}
    />
    <button class="btn px-2" onclick={() => undo()} disabled={app.undo.length === 0 || !app.snapshot?.canEdit} title="Undo (Ctrl+Z)" aria-label="Undo"><Undo size={16} /></button>
    <button class="btn px-2" onclick={redo} disabled={app.redo.length === 0 || !app.snapshot?.canEdit} title="Redo (Ctrl+Shift+Z)" aria-label="Redo"><Redo size={16} /></button>
    <!-- Always there (disabled for all but the owner), so the bar doesn't shift between networks. -->
    <button
      class="btn btn-danger px-2"
      onclick={deleteNetwork}
      disabled={!snapshot.canDelete || app.switching}
      title={snapshot.canDelete ? "Delete this network" : "Only the owner can delete this network"}
      aria-label="Delete network"><Trash size={16} /></button
    >
  {/if}
  <!-- Saving shows as a dot beside the connection status rather than replacing it: changing that text's length
       (on every node move) shifted every button in the bar. -->
  <span class="flex shrink-0 items-center gap-1.5 text-sm muted" title={saving ? "Saving…" : "Connection to the game"}>
    <span class="saving-dot" class:on={saving}></span>
    {app.inWorld ? app.player : app.socketOpen ? "Not in a world" : "Offline"}
  </span>
</header>

<style>
  .saving-dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--accent);
    opacity: 0;
    transition: opacity 400ms;
  }
  .saving-dot.on {
    opacity: 1;
  }
</style>
