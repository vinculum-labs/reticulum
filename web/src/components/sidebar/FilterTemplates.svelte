<script lang="ts">
  import Funnel from "@lucide/svelte/icons/funnel";
  import Import from "@lucide/svelte/icons/import";
  import Share from "@lucide/svelte/icons/share";
  import { confirmDialog, promptDialog } from "../../lib/dialog.svelte";
  import { app, edit, toast } from "../../lib/state.svelte";
  import { exportText, inSavingOrder, parseTemplates } from "../../lib/templates";
  import AddButton from "../controls/AddButton.svelte";
  import RemoveButton from "../controls/RemoveButton.svelte";
  import TextField from "../controls/TextField.svelte";
  import FilterField from "../inspector/FilterField.svelte";

  /** The network's filter templates, as Sheets and Blueprints: a + on the heading, a row each; a row opens to edit its filter. */

  const snapshot = $derived(app.snapshot!);
  const canEdit = $derived(snapshot.canEdit);

  /** The template open for editing (its filter shown under its row), and the one being renamed. */
  let open = $state<string | null>(null);
  let renaming = $state<string | null>(null);

  /** A new template under the first free name ("template", "template2", ...), open with its name box focused. */
  async function add() {
    let name = "template";
    for (let i = 2; name in snapshot.filters; i++) name = `template${i}`;
    const ok = await edit([{ type: "setFilter", name, expression: "" }], [{ type: "setFilter", name, expression: null }], "add filter template");
    if (ok) {
      open = name;
      renaming = name;
    }
  }

  /** Copies every template as JSON, to paste with the import here or in another world. */
  async function exportAll() {
    try {
      await navigator.clipboard.writeText(exportText(snapshot.filters));
      const n = Object.keys(snapshot.filters).length;
      toast(`Copied ${n} template${n === 1 ? "" : "s"}. Paste them with the templates' import, here or in another world.`, "info");
    } catch {
      toast("Couldn't reach the clipboard.");
    }
  }

  /**
   * Pastes templates copied with the export (or a plain name → filter map). New names are added; names already here
   * with another filter are replaced only when asked. One undoable change.
   */
  async function importSome() {
    const text = await promptDialog({
      title: "Import filter templates",
      message: "Paste templates copied with the templates' export, from this world or another.",
      confirm: "Import",
      multiline: true,
    });
    if (!text?.trim()) return;
    const incoming = parseTemplates(text);
    if (!incoming.length) {
      toast("That isn't copied filter templates.");
      return;
    }
    const clashing = incoming.filter(([name, expression]) => name in snapshot.filters && snapshot.filters[name] !== expression);
    if (clashing.length) {
      const one = clashing.length === 1;
      const ok = await confirmDialog({
        title: `Replace ${clashing.length} template${one ? "" : "s"}?`,
        message: `${clashing.map(([n]) => "@" + n).join(", ")} already ${one ? "exists" : "exist"} here with another filter. Replace ${one ? "it" : "them"} with the imported ${one ? "one" : "ones"}? Cancel imports nothing.`,
        confirm: "Replace",
      });
      if (!ok) return;
    }
    const todo = incoming.filter(([name, expression]) => snapshot.filters[name] !== expression);
    if (!todo.length) {
      toast("Those templates are all here already.", "info");
      return;
    }
    const ordered = inSavingOrder(todo, Object.keys(snapshot.filters).filter((n) => !todo.some(([t]) => t === n)));
    const ok = await edit(
      ordered.map(([name, expression]) => ({ type: "setFilter", name, expression })),
      ordered.map(([name]) => ({ type: "setFilter", name, expression: snapshot.filters[name] ?? null })).reverse(),
      "import filter templates",
    );
    if (ok) toast(`Imported ${ordered.length} template${ordered.length === 1 ? "" : "s"}.`, "info");
  }

  /** Renames a template, and everywhere it is used: other templates and every filter on every sheet. One undo. */
  function rename(from: string, to: string) {
    to = to.trim().replace(/^@/, "");
    if (!to || to === from) return;
    if (to in snapshot.filters) {
      toast(`There's already a template @${to}.`);
      return;
    }
    edit([{ type: "renameFilter", from, to }], [{ type: "renameFilter", from: to, to: from }], "rename filter template");
    if (open === from) open = to;
  }

  function save(name: string, expression: string | null) {
    const before = snapshot.filters[name] ?? null;
    if (before === expression) return;
    edit([{ type: "setFilter", name, expression }], [{ type: "setFilter", name, expression: before }], expression === null ? "delete filter template" : "filter template");
  }
</script>

<section class="border-t border-line p-3">
  <div class="mb-2 flex items-center justify-between pr-2">
    <h2 class="panel-title">Filter templates</h2>
    <div class="flex items-center gap-1">
      {#if Object.keys(snapshot.filters).length}<AddButton icon={Share} label="Export templates: copy them all as JSON" onclick={exportAll} />{/if}
      {#if canEdit}
        <AddButton icon={Import} label="Import templates: paste ones copied with the export" onclick={importSome} />
        <AddButton label="New filter template" onclick={add} />
      {/if}
    </div>
  </div>
  <ul class="flex flex-col gap-0.5">
    {#each Object.entries(snapshot.filters) as [name, expression] (name)}
      {@const isOpen = open === name}
      <li class="flex flex-col rounded-md" style={isOpen ? "background: var(--panel-2)" : ""}>
        <div class="flex items-center gap-1.5 py-1 pr-2 pl-2">
          <Funnel size={14} class="shrink-0 muted" />
          {#if renaming === name}
            <TextField class="min-w-0 flex-1 font-mono" value={name} required autofocus oncommit={(v) => rename(name, v)} onfinish={() => (renaming = null)} />
          {:else}
            <button
              class="flex min-w-0 flex-1 items-baseline gap-2 text-left"
              onclick={() => (open = isOpen ? null : name)}
              ondblclick={() => canEdit && (renaming = name)}
              onkeydown={(e) => e.key === "Delete" && canEdit && (e.preventDefault(), save(name, null))}
              title={`Use it in any filter as @${name}. Click to edit its filter, double-click to rename.`}
            >
              <code class="trim-text shrink-0">@{name}</code>
              <!-- Its filter at a glance; open, the box below shows it. -->
              {#if !isOpen}<span class="trim-text min-w-0 truncate font-mono text-xs muted">{expression || "everything"}</span>{/if}
            </button>
          {/if}
          {#if isOpen && canEdit}<RemoveButton label={`Delete template @${name}`} onclick={() => save(name, null)} />{/if}
        </div>
        {#if isOpen}
          <div class="px-2 pb-2">
            <FilterField value={expression} disabled={!canEdit} placeholder="everything" onchange={(v) => save(name, v)} />
          </div>
        {/if}
      </li>
    {:else}
      <li class="text-xs muted">None yet. A template is a named filter: use it in any filter as <code>@name</code>.</li>
    {/each}
  </ul>
</section>
