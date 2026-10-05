<script lang="ts">
  import { NAME_LIMIT } from "../../lib/kinds";
  import Help from "./Help.svelte";
  import { app, deleteInstance, edit, nodeTitle, openSheet, showNode, updateNodeEdit } from "../../lib/state.svelte";
  import { confirmDialog } from "../../lib/dialog.svelte";
  import { instancesOf, slotsOf } from "../../lib/blueprints";
  import { sheetPath } from "../../lib/tree";
  import type { InstanceSettings, GraphNode, Sheet } from "../../lib/types";
  import Trash from "@lucide/svelte/icons/trash";
  import InspectorPanel from "./InspectorPanel.svelte";
  import { KIND_ICON } from "../../lib/icons";
  import Field from "./Field.svelte";
  import TextField from "../controls/TextField.svelte";

  /** An open instance: its slots filled so far, and the ways to change more than that. */
  let { sheet, instance, blueprint }: { sheet: Sheet; instance: GraphNode & { settings: InstanceSettings }; blueprint: Sheet } = $props();

  const canEdit = $derived(app.snapshot?.canEdit ?? false);
  const slots = $derived(slotsOf(blueprint));
  const filled = $derived(slots.filter((s) => instance.settings.bindings?.[s.id]).length);
  const instances = $derived(app.snapshot ? instancesOf(app.snapshot, blueprint.id).length : 0);

  /** This instance's own name (its node's label); blank goes back to the blueprint's. */
  function rename(name: string) {
    updateNodeEdit(sheet.id, instance, { ...instance, label: name || undefined }, "rename");
  }

  async function convert() {
    const network = app.current;
    const ok = await confirmDialog({
      title: `Turn “${nodeTitle(instance)}” into a normal sheet?`,
      message: `It stops following “${blueprint.name}”: its nodes become its own, to edit freely. Filled slots become device nodes for their blocks — empty ones point at where the blueprint was laid out. This can't be undone.`,
      confirm: "Turn into a sheet",
    });
    if (!ok || app.current !== network) return;
    const results = await edit([{ type: "detachInstance", sheet: sheet.id, node: instance.id }]);
    if (results?.[0]) openSheet(results[0] as string);
  }
</script>

<InspectorPanel title={nodeTitle(instance)} subtitle={`The blueprint “${blueprint.name}”, placed in ${sheetPath(app.snapshot!, sheet)}.`}>
  {#snippet icon()}
    {@const CopyIcon = KIND_ICON.instance}
    <span class="shrink-0 muted"><CopyIcon size={24} /></span>
  {/snippet}
  {#snippet actions()}
    {#if canEdit}<button class="btn btn-danger px-2" onclick={() => deleteInstance(sheet, instance)} title="Remove the blueprint from here" aria-label="Remove"><Trash size={16} /></button>{/if}
  {/snippet}
  <Field label="Name" hint="Optional — the blueprint's name is shown otherwise.">
    <TextField value={instance.label} maxlength={NAME_LIMIT} disabled={!canEdit} placeholder={blueprint.name} oncommit={rename} />
  </Field>
  <p class="text-sm">
    {#if slots.length}
      {filled} of {slots.length} slot{slots.length === 1 ? "" : "s"} filled.
      {filled < slots.length ? "Drop devices from the list on the empty (red) ones — routes through an empty slot don't run." : ""}
    {:else}
      Its blueprint has no slots: it works on nothing but what is wired to its pins.
    {/if}
  </p>
  <Help>Only its slots can be changed here. Everything else is the blueprint's{instances > 1 ? `, the same in all ${instances} places it's placed` : ""}.</Help>
  <div class="flex flex-wrap gap-2">
    <button class="btn text-sm" onclick={() => openSheet(blueprint.id)}>Edit the blueprint</button>
    <button class="btn text-sm" onclick={() => showNode(sheet.id, instance.id)}>Show its node</button>
    <!-- Not for one placed on a blueprint: sheets don't go in blueprints. -->
    {#if canEdit && !sheet.blueprint}
      <button class="btn text-sm" onclick={convert}>Turn into a normal sheet…</button>
    {/if}
  </div>
</InspectorPanel>
