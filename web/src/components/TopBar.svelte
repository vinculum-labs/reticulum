<script lang="ts">
  import { app, edit, selectNetwork, undo, redo, request, loadNetworks, toast } from "../lib/state.svelte";

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
    <button class="btn" onclick={undo} disabled={app.undo.length === 0} title="Undo (Ctrl+Z)">Undo</button>
    <button class="btn" onclick={redo} disabled={app.redo.length === 0} title="Redo (Ctrl+Shift+Z)">Redo</button>
    {#if snapshot.role === "owner"}
      <button class="btn btn-danger" onclick={deleteNetwork}>Delete network</button>
    {/if}
  {/if}
  <span class="text-sm muted" title="Connection to the game">
    {app.busy > 0 ? "Saving…" : app.inWorld ? app.player : app.socketOpen ? "Not in a world" : "Offline"}
  </span>
</header>
