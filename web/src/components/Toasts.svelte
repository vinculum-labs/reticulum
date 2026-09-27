<script lang="ts">
  import { app } from "../lib/state.svelte";
</script>

<div class="pointer-events-none fixed right-4 bottom-4 z-50 flex max-w-md flex-col gap-2" aria-live="polite">
  {#each app.toasts as t (t.id)}
    <div
      class="pointer-events-auto rounded-lg border px-3 py-2 shadow-lg"
      style="background: var(--panel); border-color: {t.kind === 'error' ? 'var(--bad)' : 'var(--line)'}"
    >
      {t.text}
    </div>
  {/each}
</div>
