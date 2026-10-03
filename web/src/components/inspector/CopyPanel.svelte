<script lang="ts">
  import { app, deleteCopy, edit, nodeTitle, openSheet, rebase, showNode } from "../../lib/state.svelte";
  import { confirmDialog } from "../../lib/dialog.svelte";
  import { copiesOf, slotsOf } from "../../lib/blueprints";
  import { sheetPath } from "../../lib/tree";
  import type { CopySettings, GraphNode, Sheet } from "../../lib/types";
  import Trash from "@lucide/svelte/icons/trash";
  import InspectorPanel from "./InspectorPanel.svelte";
  import { KIND_ICON } from "../../lib/icons";
  import Field from "./Field.svelte";
  import TextField from "../controls/TextField.svelte";

  /** An open copy: its slots filled so far, and the ways to change more than that. */
  let { sheet, copy, blueprint }: { sheet: Sheet; copy: GraphNode & { settings: CopySettings }; blueprint: Sheet } = $props();

  const canEdit = $derived(app.snapshot?.canEdit ?? false);
  const slots = $derived(slotsOf(blueprint));
  const filled = $derived(slots.filter((s) => copy.settings.bindings?.[s.id]).length);
  const copies = $derived(app.snapshot ? copiesOf(app.snapshot, blueprint.id).length : 0);

  /** This copy's own name (its node's label); blank goes back to the blueprint's. */
  function rename(name: string) {
    const { after, before } = rebase(copy, { ...copy, label: name || undefined });
    edit([{ type: "updateNode", sheet: sheet.id, node: after }], [{ type: "updateNode", sheet: sheet.id, node: before }], "rename copy");
  }

  async function convert() {
    const network = app.current;
    const ok = await confirmDialog({
      title: `Turn “${nodeTitle(copy)}” into a normal sheet?`,
      message: `It stops following “${blueprint.name}”: its nodes become its own, to edit freely. Filled slots become device nodes for their blocks — empty ones point at where the blueprint was laid out. This can't be undone.`,
      confirm: "Turn into a sheet",
    });
    if (!ok || app.current !== network) return;
    const results = await edit([{ type: "convertCopy", sheet: sheet.id, node: copy.id }]);
    if (results?.[0]) openSheet(results[0] as string);
  }
</script>

<InspectorPanel title={nodeTitle(copy)} subtitle={`A copy of the blueprint “${blueprint.name}”, in ${sheetPath(app.snapshot!, sheet)}.`}>
  {#snippet icon()}
    {@const CopyIcon = KIND_ICON.copy}
    <span class="shrink-0 muted"><CopyIcon size={24} /></span>
  {/snippet}
  {#snippet actions()}
    {#if canEdit}<button class="btn btn-danger px-2" onclick={() => deleteCopy(sheet, copy)} title="Delete this copy" aria-label="Delete copy"><Trash size={16} /></button>{/if}
  {/snippet}
  <Field label="Name" hint="Optional — the blueprint's name is shown otherwise.">
    <TextField value={copy.label} disabled={!canEdit} placeholder={blueprint.name} oncommit={rename} />
  </Field>
  <p class="text-sm">
    {#if slots.length}
      {filled} of {slots.length} slot{slots.length === 1 ? "" : "s"} filled.
      {filled < slots.length ? "Drop devices from the list on the empty (red) ones — routes through an empty slot don't run." : ""}
    {:else}
      Its blueprint has no slots: it works on nothing but what is wired to its pins.
    {/if}
  </p>
  <p class="text-xs muted">
    Only its slots can be changed here. Everything else is the blueprint's{copies > 1 ? `, shared by its ${copies} copies` : ""}.
  </p>
  <div class="flex flex-wrap gap-2">
    <button class="btn text-sm" onclick={() => openSheet(blueprint.id)}>Edit the blueprint</button>
    <button class="btn text-sm" onclick={() => showNode(sheet.id, copy.id)}>Show its node</button>
    {#if canEdit}
      <button class="btn text-sm" onclick={convert}>Turn into a normal sheet…</button>
    {/if}
  </div>
</InspectorPanel>
