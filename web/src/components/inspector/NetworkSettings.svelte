<script lang="ts">
  import { app, edit } from "../../lib/state.svelte";
  import type { Member } from "../../lib/types";
  import Field from "./Field.svelte";

  const snapshot = $derived(app.snapshot!);
  const canEdit = $derived(snapshot.canEdit);
  const canAdmin = $derived(snapshot.canAdmin);

  let password = $state("");
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

<div class="flex flex-col gap-3">
  <div class="flex items-center justify-between">
    <h2 class="text-base font-semibold">Network settings</h2>
    <button class="btn text-sm" onclick={() => (app.panel = null)}>Close</button>
  </div>

  <Field label="Name">
    <input
      value={snapshot.name}
      disabled={!canEdit}
      onchange={(e) => e.currentTarget.value.trim() && edit([{ type: "renameNetwork", name: e.currentTarget.value.trim() }], [{ type: "renameNetwork", name: snapshot.name }], "rename network")}
    />
  </Field>

  <dl class="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
    <dt class="muted">Owner</dt>
    <dd>{snapshot.owner || "none (open to everyone)"}</dd>
    <dt class="muted">Your role</dt>
    <dd>{snapshot.role}</dd>
  </dl>

  <Field label="Access" hint="Public: anyone can use it. Password: anyone who knows the password (/reticulum join). Private: members only.">
    <select value={snapshot.access} disabled={!canAdmin} onchange={(e) => setAccess(e.currentTarget.value)}>
      <option value="public">Public</option>
      <option value="password" disabled={!snapshot.hasPassword}>Password{snapshot.hasPassword ? "" : " (set one first)"}</option>
      <option value="private">Private</option>
    </select>
  </Field>

  {#if canAdmin}
    <Field label={snapshot.hasPassword ? "Change password" : "Set a password"} hint="Stored salted and hashed; nobody can read it back.">
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
          <select
            value={member.role}
            onchange={(e) =>
              edit([{ type: "setMember", player: member.id, role: e.currentTarget.value }], [{ type: "setMember", player: member.id, role: member.role }], "member role")}
          >
            {#each ROLES as r}<option value={r.value}>{r.label}</option>{/each}
          </select>
          <button class="text-sm muted" title="Remove member" onclick={() => edit([{ type: "removeMember", id: member.id }], [{ type: "setMember", player: member.id, role: member.role }], "remove member")}>✕</button>
        </div>
      {:else}
        <p class="text-sm muted">No members yet.</p>
      {/each}
      <form class="flex gap-2" onsubmit={(e) => (e.preventDefault(), addMember())}>
        <input class="min-w-0 flex-1" placeholder="Player name" bind:value={newMember} />
        <select bind:value={newRole}>
          {#each ROLES as r}<option value={r.value}>{r.label}</option>{/each}
        </select>
        <button class="btn" type="submit" disabled={!newMember.trim()}>Add</button>
      </form>
      <ul class="text-xs muted">
        {#each ROLES as r}<li><b>{r.label}</b>: {r.hint}</li>{/each}
      </ul>
    </section>
  {:else}
    <p class="text-sm muted">Only the network's admins can change access and members.</p>
  {/if}
</div>
