<script lang="ts">
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
    setWireStyle,
    WIRE_STYLE_LABEL,
    type WireStyle,
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
    <span class="text-sm muted">{snapshot.running ? "Running" : snapshot.enabled ? "No controller loaded" : "Off"}</span>
    {#if power?.enabled && !power.powered}
      <span class="rounded px-2 py-1 text-sm" style="background: var(--bad); color: white" title="The controller has too little FE for this network; only energy flows run">No power</span>
    {/if}
    <button
      class="btn"
      style={app.panel === "dashboard" ? "border-color: var(--accent); color: var(--accent)" : ""}
      onclick={() => (app.panel = app.panel === "dashboard" ? null : "dashboard")}
      title="Busiest and waiting flows, what is switched off, tick cost and the overflow buffer">Dashboard</button
    >
    <button
      class="btn"
      style={app.panel === "identified" ? "border-color: var(--accent); color: var(--accent)" : ""}
      onclick={() => (app.panel = app.panel === "identified" ? null : "identified")}
      title="What is lit up in the world">Identified{app.snapshot?.identified?.length ? ` (${app.snapshot.identified.length})` : ""}</button
    >
    <button
      class="btn"
      style={app.panel === "network" ? "border-color: var(--accent); color: var(--accent)" : ""}
      onclick={() => (app.panel = app.panel === "network" ? null : "network")}
      title="Name, access, password and members">Settings</button
    >
    <button
      class="btn"
      onclick={() => setRateUnit(app.rateUnit === "t" ? "s" : "t")}
      title="Show rates per tick or per second">/{app.rateUnit}</button
    >
    <select
      class="text-sm"
      value={app.wireStyle}
      title="How wires are drawn between pins (remembered in this browser)"
      onchange={(e) => setWireStyle(e.currentTarget.value as WireStyle)}
    >
      {#each Object.entries(WIRE_STYLE_LABEL) as [style, label] (style)}<option value={style}>Wires: {label}</option>{/each}
    </select>
    <button class="btn" onclick={undo} disabled={app.undo.length === 0} title="Undo (Ctrl+Z)">Undo</button>
    <button class="btn" onclick={redo} disabled={app.redo.length === 0} title="Redo (Ctrl+Shift+Z)">Redo</button>
    {#if snapshot.role === "owner"}
      <button class="btn btn-danger" onclick={deleteNetwork}>Delete network</button>
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
