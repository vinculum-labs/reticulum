<script lang="ts">
  import { app, edit, setSheetEnabled, toast } from "../../lib/state.svelte";
  import type { Sheet } from "../../lib/types";
  import NumberField from "../controls/NumberField.svelte";
  import Switch from "../controls/Switch.svelte";
  import TextField from "../controls/TextField.svelte";
  import Field from "./Field.svelte";
  import InspectorPanel from "./InspectorPanel.svelte";
  import RedstoneEditor from "./RedstoneEditor.svelte";
  import SheetErrors from "./SheetErrors.svelte";

  let { sheet }: { sheet: Sheet } = $props();

  const snapshot = $derived(app.snapshot!);
  const canEdit = $derived(snapshot.canEdit);
  const folders = $derived([...new Set(snapshot.sheets.map((s) => s.folder).filter((f): f is string => !!f))].sort());

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

<InspectorPanel title="Sheet">
  <Field label="Name">
    <TextField value={sheet.name} required disabled={!canEdit} oncommit={(v) => update({ name: v }, { name: sheet.name }, "rename sheet")} />
  </Field>
  <Switch label="On" checked={sheet.enabled !== false} disabled={!canEdit} onchange={(on) => setSheetEnabled(sheet, on)} />
  <Field label="Item operations every" hint="Ticks between item operations; sources can override it. Fluids, chemicals and energy move every tick.">
    <NumberField value={sheet.interval ?? 10} min={1} max={72_000} width="md" disabled={!canEdit}
      oncommit={(v) => update({ interval: v }, { interval: sheet.interval ?? 10 }, "sheet interval")} />
  </Field>
  <Field label="Folder" hint="Use / for folders inside folders, e.g. smelting/ores. Leave empty for the top level.">
    <TextField value={sheet.folder} placeholder="top level" suggestions={folders} disabled={!canEdit}
      oncommit={(v) => update({ folder: v }, { folder: sheet.folder ?? "" }, "move sheet")} />
  </Field>
  {#if !sheet.subsheet}
    <RedstoneEditor
      value={sheet.redstone}
      disabled={!canEdit}
      defaultLabel="the controller"
      onchange={(redstone) => update({ redstone }, { redstone: sheet.redstone ?? { mode: "ignored" } }, "sheet redstone")}
    />
    {#if app.sheetsOff.includes(sheet.id)}<p class="text-xs text-signal" role="status">Redstone has this sheet switched off right now.</p>{/if}
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
  <SheetErrors sheetId={sheet.id} errors={sheet.errors} />
  <button class="btn self-start text-sm" onclick={() => (app.panel = "network")}>Network settings…</button>
</InspectorPanel>
