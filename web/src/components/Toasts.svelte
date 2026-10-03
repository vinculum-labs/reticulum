<script lang="ts">
  import { app } from "../lib/state.svelte";
  import RemoveButton from "./controls/RemoveButton.svelte";

  function dismiss(id: number) {
    app.toasts = app.toasts.filter((t) => t.id !== id);
  }
</script>

<div class="pointer-events-none fixed right-4 bottom-4 z-50 flex max-w-md flex-col gap-2">
  {#each app.toasts as t (t.id)}
    <div
      class="pointer-events-auto flex items-start gap-2 rounded-lg border bg-panel px-3 py-2 shadow-lg {t.kind === 'error' ? 'border-bad' : 'border-line'}"
      role={t.kind === "error" ? "alert" : "status"}
    >
      <span class="flex-1">{t.text}</span>
      {#if t.action}
        {@const action = t.action}
        <button class="btn shrink-0 text-xs" onclick={() => (action.run(), dismiss(t.id))}>{action.label}</button>
      {/if}
      <RemoveButton label="Dismiss" onclick={() => dismiss(t.id)} />
    </div>
  {/each}
</div>
