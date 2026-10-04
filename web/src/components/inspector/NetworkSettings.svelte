<script lang="ts">
  import Help from "./Help.svelte";
  import Plus from "@lucide/svelte/icons/plus";
  import { app, edit } from "../../lib/state.svelte";
  import type { Member } from "../../lib/types";
  import RemoveButton from "../controls/RemoveButton.svelte";
  import Select from "../controls/Select.svelte";
  import TextField from "../controls/TextField.svelte";
  import Field from "./Field.svelte";
  import InspectorPanel from "./InspectorPanel.svelte";
  import Segmented from "../controls/Segmented.svelte";

  const snapshot = $derived(app.snapshot!);
  const canEdit = $derived(snapshot.canEdit);
  const canAdmin = $derived(snapshot.canAdmin);

  let password = $state("");
  const power = $derived(app.power ?? snapshot.power);

  const ANTENNA = ["None: no wireless connectors", "Tier 1: 128 blocks, same dimension", "Tier 2: the whole dimension", "Tier 3: every dimension"];
  const wireless = $derived(snapshot.connectors.filter((c) => c.reach === "wireless").length);
  const outOfRange = $derived(snapshot.connectors.filter((c) => c.reach === "out_of_range").length);
  let newMember = $state("");
  let newRole = $state<Member["role"]>("user");

  const ROLES: { value: Member["role"]; label: string; hint: string }[] = [
    { value: "viewer", label: "Viewer", hint: "sees sheets and throughput" },
    { value: "user", label: "User", hint: "can also join connectors to the network" },
    { value: "editor", label: "Editor", hint: "can also change sheets, names and filters" },
    { value: "admin", label: "Admin", hint: "can also change access and members" },
  ];

  function setAccess(mode: string) {
    edit([{ type: "setAccess", mode }], [{ type: "setAccess", mode: snapshot.access }], "access");
  }

  async function savePassword() {
    if (!password) return;
    await edit([{ type: "setPassword", password }]);
    password = "";
  }

  async function addMember() {
    const player = newMember.trim();
    if (!player) return;
    const ok = await edit([{ type: "setMember", player, role: newRole }]);
    if (ok) newMember = "";
  }
</script>

<InspectorPanel title="Network settings" onclose={() => (app.panel = null)}>
  <Field label="Name">
    <TextField value={snapshot.name} required disabled={!canEdit}
      oncommit={(v) => edit([{ type: "renameNetwork", name: v }], [{ type: "renameNetwork", name: snapshot.name }], "rename network")} />
  </Field>

  <Field label="Redstone at the controller" hint="What a redstone signal into the controller block does to the whole network.">
    <Segmented
      label="Redstone at the controller"
      size="xs"
      value={snapshot.redstone ?? "ignored"}
      disabled={!canEdit}
      options={[
        { value: "ignored", label: "Ignore" },
        { value: "high", label: "On while powered" },
        { value: "low", label: "Off while powered" },
      ]}
      onchange={(redstone) =>
        edit([{ type: "setNetworkRedstone", redstone }], [{ type: "setNetworkRedstone", redstone: snapshot.redstone ?? "ignored" }], "network redstone")}
    />
  </Field>

  {#if power?.enabled}
    <section class="flex flex-col gap-1 text-sm">
      <h3 class="panel-title">Power</h3>
      <div class="h-2 overflow-hidden rounded bg-line">
        <div class="h-full" style="width: {Math.min(100, (power.stored / Math.max(1, power.capacity)) * 100)}%; background: {power.powered ? 'var(--good)' : 'var(--bad)'}"></div>
      </div>
      <div class="flex justify-between tabular-nums">
        <span>{power.stored.toLocaleString()} / {power.capacity.toLocaleString()} FE</span>
        <span>{power.upkeep} FE/t upkeep</span>
      </div>
      {#if !power.powered}<p class="text-xs text-bad" role="status">Not enough FE: only energy flows run until the controller is charged.</p>{/if}
      <Help>The controller takes FE from anything that pushes into it and pulls from FE sources next to it. Item operations cost extra.</Help>
    </section>
  {/if}

  <dl class="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1 break-words text-sm">
    <dt class="muted">Antenna</dt>
    <dd title="Right-click the controller with an antenna to change it">{ANTENNA[snapshot.antenna ?? 0]}</dd>
    <dt class="muted">Connectors</dt>
    <dd>
      {snapshot.connectors.length}{wireless ? `, ${wireless} wireless` : ""}{outOfRange
        ? `, ${outOfRange} out of range`
        : ""}
    </dd>
    <dt class="muted">Owner</dt>
    <dd>
      {#if snapshot.owner}{snapshot.owner}{:else}none yet<span class="muted">{" — "}a machine placed its controller. Open the controller in the game to make it yours.</span>{/if}
    </dd>
    <dt class="muted">Your role</dt>
    <dd>{snapshot.role}</dd>
  </dl>

  <Field label="Access" hint="Public: anyone can use it. Password: anyone who knows the password (/reticulum join). Private: members only.">
    <Select
      value={snapshot.access}
      disabled={!canAdmin}
      options={[
        { value: "public", label: "Public" },
        { value: "password", label: `Password${snapshot.hasPassword ? "" : " (set one first)"}`, disabled: !snapshot.hasPassword },
        { value: "private", label: "Private" },
      ]}
      onchange={setAccess}
    />
  </Field>

  {#if canAdmin}
    <Field label={snapshot.hasPassword ? "Change password" : "Set a password"} hint="Stored salted and hashed — nobody can read it back.">
      <form class="flex gap-2" onsubmit={(e) => (e.preventDefault(), savePassword())}>
        <input class="min-w-0 flex-1" type="password" autocomplete="new-password" bind:value={password} />
        <button class="btn" type="submit" disabled={!password}>Save</button>
      </form>
    </Field>

    <section class="flex flex-col gap-2">
      <h3 class="panel-title">Members</h3>
      {#each snapshot.members ?? [] as member (member.id)}
        <div class="flex items-center gap-2 text-sm">
          <span class="min-w-0 flex-1 truncate" title={member.id}>{member.name}</span>
          <Select
            value={member.role}
            options={ROLES}
            title={`${member.name}'s role`}
            onchange={(role) => edit([{ type: "setMember", player: member.id, role }], [{ type: "setMember", player: member.id, role: member.role }], "member role")}
          />
          <RemoveButton label={`Remove ${member.name}`} onclick={() => edit([{ type: "removeMember", id: member.id }], [{ type: "setMember", player: member.id, role: member.role }], "remove member")} />
        </div>
      {:else}
        <p class="text-sm muted">No members yet.</p>
      {/each}
      <form class="flex gap-2" onsubmit={(e) => (e.preventDefault(), addMember())}>
        <input class="min-w-0 flex-1" placeholder="Player name" bind:value={newMember} />
        <Select bind:value={newRole} options={ROLES} title="Role" />
        <button class="btn flex shrink-0 items-center px-2" type="submit" disabled={!newMember.trim()} title="Add this player" aria-label="Add member"><Plus size={16} /></button>
      </form>
      <ul class="text-xs muted">
        {#each ROLES as r}<li><b>{r.label}</b>: {r.hint}</li>{/each}
      </ul>
    </section>
  {:else}
    <p class="text-sm muted">Only the network's admins can change access and members.</p>
  {/if}
</InspectorPanel>
