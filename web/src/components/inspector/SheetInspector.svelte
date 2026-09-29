<script lang="ts">
  import { app, edit, toast } from "../../lib/state.svelte";
  import type { Sheet } from "../../lib/types";
  import Field from "./Field.svelte";
  import RedstoneEditor from "./RedstoneEditor.svelte";

  let { sheet }: { sheet: Sheet } = $props();

  const snapshot = $derived(app.snapshot!);
  const canEdit = $derived(snapshot.canEdit);
  const orphans = $derived(snapshot.devices.filter((d) => d.state !== "active").length);

  async function copyJson() {
    const { errors: _errors, ...plain } = sheet;
    try {
      await navigator.clipboard.writeText(JSON.stringify(plain, null, 2));
      toast("Copied. Paste it into “Import sheet…” here or in another world.", "info");
    } catch {
      toast("Couldn't reach the clipboard.");
    }
  }

  function update(patch: Record<string, unknown>, before: Record<string, unknown>, label: string) {
    edit([{ type: "updateSheet", sheet: sheet.id, ...patch }], [{ type: "updateSheet", sheet: sheet.id, ...before }], label);
  }
</script>

<div class="flex flex-col gap-3">
  <h2 class="text-base font-semibold">Sheet</h2>
  <Field label="Name">
    <input value={sheet.name} disabled={!canEdit} onchange={(e) => e.currentTarget.value.trim() && update({ name: e.currentTarget.value.trim() }, { name: sheet.name }, "rename sheet")} />
  </Field>
  <label class="flex items-center gap-2 text-sm">
    <input
      type="checkbox"
      checked={sheet.enabled !== false}
      disabled={!canEdit}
      onchange={(e) => update({ enabled: e.currentTarget.checked }, { enabled: sheet.enabled !== false }, "sheet on/off")}
    />
    On
  </label>
  <Field label="Item operations every" hint="Ticks between item operations; sources can override it. Fluids, chemicals and energy move every tick.">
    <input
      type="number"
      min="1"
      class="w-24"
      value={sheet.interval ?? 10}
      disabled={!canEdit}
      onchange={(e) => update({ interval: Math.max(1, Number(e.currentTarget.value) || 1) }, { interval: sheet.interval ?? 10 }, "sheet interval")}
    />
  </Field>
  <Field label="Folder" hint="Use / for folders inside folders, e.g. smelting/ores. Leave empty for the top level.">
    <input
      value={sheet.folder ?? ""}
      disabled={!canEdit}
      placeholder="top level"
      list="reticulum-folders"
      onchange={(e) => update({ folder: e.currentTarget.value }, { folder: sheet.folder ?? "" }, "move sheet")}
    />
    <datalist id="reticulum-folders">
      {#each [...new Set(snapshot.sheets.map((s) => s.folder).filter(Boolean))] as folder}<option value={folder}></option>{/each}
    </datalist>
  </Field>
  {#if !sheet.subsheet}
    <RedstoneEditor
      value={sheet.redstone}
      disabled={!canEdit}
      defaultLabel="the controller"
      onchange={(redstone) => update({ redstone }, { redstone: sheet.redstone ?? { mode: "ignored" } }, "sheet redstone")}
    />
    {#if app.sheetsOff.includes(sheet.id)}<p class="text-xs" style="color: var(--c-signal)">Redstone has this sheet switched off right now.</p>{/if}
    {#if sheet.folder}
      <details class="text-sm">
        <summary class="cursor-pointer">Folder “{sheet.folder}” redstone</summary>
        <div class="mt-2">
          <RedstoneEditor
            value={snapshot.folderRedstone?.[sheet.folder]}
            disabled={!canEdit}
            defaultLabel="the controller"
            onchange={(redstone) =>
              edit(
                [{ type: "setFolderRedstone", folder: sheet.folder, redstone }],
                [{ type: "setFolderRedstone", folder: sheet.folder, redstone: snapshot.folderRedstone?.[sheet.folder!] ?? { mode: "ignored" } }],
                "folder redstone",
              )}
          />
          <p class="mt-1 text-xs muted">Applies to every sheet in this folder and the folders under it.</p>
        </div>
      </details>
    {/if}
  {/if}
  <label class="flex items-start gap-2 text-sm">
    <input
      type="checkbox"
      checked={sheet.subsheet === true}
      disabled={!canEdit}
      onchange={(e) => update({ subsheet: e.currentTarget.checked }, { subsheet: sheet.subsheet === true }, "sub-sheet")}
    />
    <span>
      Use as sub-sheet
      <span class="block text-xs muted">It stops running on its own; other sheets place it as a node. Add Sheet input and Sheet output nodes for its pins.</span>
    </span>
  </label>
  <button class="btn self-start text-sm" onclick={copyJson} title="To paste into “Import sheet…” here or in another world">Copy as JSON</button>
  {#if sheet.errors?.length}
    <div class="rounded-md border p-2 text-sm" style="border-color: var(--bad)">
      {#each sheet.errors as error}<div>{error}</div>{/each}
    </div>
  {/if}

  <hr style="border-color: var(--line)" />
  <h2 class="text-base font-semibold">Network</h2>
  <dl class="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
    <dt class="muted">Access</dt><dd>{snapshot.access}</dd>
    <dt class="muted">Owner</dt><dd>{snapshot.owner || "none"}</dd>
    <dt class="muted">Your role</dt><dd>{snapshot.role}</dd>
    <dt class="muted">Devices</dt><dd>{snapshot.devices.length}{orphans ? ` (${orphans} orphaned)` : ""}</dd>
    <dt class="muted">Connectors</dt><dd>{snapshot.connectors.length}</dd>
    <dt class="muted">Overflow</dt>
    <dd>
      {#if snapshot.overflow.items.length || snapshot.overflow.fluids.length || snapshot.overflow.chemicals?.length}
        {#each snapshot.overflow.items as s}<div>{s.count}× {s.id}</div>{/each}
        {#each snapshot.overflow.fluids as f}<div>{f.amount} mB {f.id}</div>{/each}
        {#each snapshot.overflow.chemicals ?? [] as c}<div>{c.amount} mB {c.id}</div>{/each}
      {:else}
        empty
      {/if}
    </dd>
  </dl>
  <p class="text-xs muted">
    Tip: drag devices from the left onto the canvas, drag from an output (right) to an input (left) to wire, Delete removes, Ctrl+Z undoes.
  </p>
</div>
