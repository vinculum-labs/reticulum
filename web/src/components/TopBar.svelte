<script lang="ts">
  import LayoutDashboard from "@lucide/svelte/icons/layout-dashboard";
  import Lightbulb from "@lucide/svelte/icons/lightbulb";
  import Play from "@lucide/svelte/icons/play";
  import Stop from "@lucide/svelte/icons/circle-stop";
  import Redo from "@lucide/svelte/icons/redo-2";
  import Settings from "@lucide/svelte/icons/settings";
  import Trash from "@lucide/svelte/icons/trash";
  import Undo from "@lucide/svelte/icons/undo-2";
  import Unplug from "@lucide/svelte/icons/unplug";
  import ZapOff from "@lucide/svelte/icons/zap-off";
  import {
    app,
    edit,
    selectNetwork,
    undo,
    redo,
    request,
    loadNetworks,
    toast,
    setRateUnit,
  } from "../lib/state.svelte";

  const snapshot = $derived(app.snapshot);

  async function deleteNetwork() {
    if (!snapshot) return;
    if (!confirm(`Delete the network "${snapshot.name}"? Its sheets, names and settings are gone for good.`)) return;
    try {
      await request("deleteNetwork");
      app.current = null;
      app.snapshot = null;
      await loadNetworks(null);
    } catch (e) {
      toast((e as Error).message);
    }
  }
  const power = $derived(app.power ?? app.snapshot?.power);

  /** The network's state as an icon, a color and a tooltip. */
  function networkStatus(running: boolean, enabled: boolean, unpowered: boolean) {
    if (running && unpowered) {
      return { icon: ZapOff, color: "var(--bad)", title: "No power: the controller has too little FE for this network, so only energy flows run" };
    }
    if (running) return { icon: Play, color: "var(--good)", title: "Running" };
    if (enabled) return { icon: Unplug, color: "var(--warn)", title: "No controller loaded: the controller's chunk isn't loaded, so nothing runs" };
    return { icon: Stop, color: "var(--muted)", title: "Off: switched off with the On checkbox" };
  }

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

<header class="flex items-center gap-3 border-b px-3 py-2" style="background: var(--panel); border-color: var(--line)">
  <span class="font-semibold tracking-wide">Reticulum</span>
  <nav class="flex min-w-0 flex-1 gap-1 overflow-x-auto" aria-label="Networks">
    {#each app.networks as n (n.id)}
      <button
        class="btn flex items-center gap-2"
        style={n.id === app.current ? "border-color: var(--accent); color: var(--accent)" : ""}
        onclick={() => selectNetwork(n.id)}
        title={`${n.access}, owner ${n.owner || "none"}, your role: ${n.role}`}
      >
        <span class="inline-block h-2 w-2 rounded-full" style="background: {n.running ? 'var(--good)' : 'var(--muted)'}"></span>
        {n.name}
      </button>
    {/each}
  </nav>
  {#if snapshot}
    <label class="flex items-center gap-1 text-sm" title="Switch the whole network on or off">
      <input
        type="checkbox"
        checked={snapshot.enabled}
        disabled={!snapshot.canEdit}
        onchange={(e) => edit([{ type: "setNetworkEnabled", enabled: e.currentTarget.checked }], [{ type: "setNetworkEnabled", enabled: snapshot.enabled }], "network on/off")}
      />
      On
    </label>
    <!-- One fixed-size icon for the network's state, so nothing beside it shifts when the state changes. -->
    {@const status = networkStatus(snapshot.running, snapshot.enabled, !!power?.enabled && !power.powered)}
    <span class="flex h-5 w-5 shrink-0 items-center justify-center" style="color: {status.color}" title={status.title} role="img" aria-label={status.title}>
      <status.icon size={16} />
    </span>
    <button
      class="btn flex items-center gap-1"
      style={app.panel === "dashboard" ? "border-color: var(--accent); color: var(--accent)" : ""}
      onclick={() => (app.panel = app.panel === "dashboard" ? null : "dashboard")}
      title="Busiest and waiting flows, what is switched off, tick cost and the overflow buffer"><LayoutDashboard size={14} />Dashboard</button
    >
    <button
      class="btn flex items-center gap-1"
      style={app.panel === "identified" ? "border-color: var(--accent); color: var(--accent)" : ""}
      onclick={() => (app.panel = app.panel === "identified" ? null : "identified")}
      title="What is lit up in the world"><Lightbulb size={14} />Identified{app.snapshot?.identified?.length ? ` (${app.snapshot.identified.length})` : ""}</button
    >
    <button
      class="btn flex items-center gap-1"
      style={app.panel === "network" ? "border-color: var(--accent); color: var(--accent)" : ""}
      onclick={() => (app.panel = app.panel === "network" ? null : "network")}
      title="Name, access, password and members"><Settings size={14} />Settings</button
    >
    <button
      class="btn"
      onclick={() => setRateUnit(app.rateUnit === "t" ? "s" : "t")}
      title={app.rateUnit === "t" ? "Rates per tick (t); click for per second" : "Rates per second (s); click for per tick"}>{app.rateUnit}</button
    >
    <button class="btn px-2" onclick={undo} disabled={app.undo.length === 0} title="Undo (Ctrl+Z)" aria-label="Undo"><Undo size={16} /></button>
    <button class="btn px-2" onclick={redo} disabled={app.redo.length === 0} title="Redo (Ctrl+Shift+Z)" aria-label="Redo"><Redo size={16} /></button>
    {#if snapshot.role === "owner"}
      <button class="btn btn-danger px-2" onclick={deleteNetwork} title="Delete this network" aria-label="Delete network"><Trash size={16} /></button>
    {/if}
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
