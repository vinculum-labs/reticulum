<script lang="ts">
  import { tick } from "svelte";
  import { answerDialog, cancelDialog, dialogs, type OpenDialog } from "../lib/dialog.svelte";
  import Modal, { LARGE_HEIGHT, LARGE_WIDTH } from "./Modal.svelte";

  /** The confirm and prompt dialogs (lib/dialog.svelte.ts opens them). A prompt takes input, so it can be resized. */
  let field: HTMLInputElement | HTMLTextAreaElement | undefined = $state();
  let cancelButton: HTMLButtonElement | undefined = $state();
  let confirmButton: HTMLButtonElement | undefined = $state();
  let value = $state("");

  const open = $derived(dialogs.open);

  /** Fills in and focuses a dialog as it opens. A destructive question starts on Cancel, so Enter doesn't confirm it. */
  function opened(dialog: OpenDialog) {
    value = dialog.kind === "prompt" ? (dialog.value ?? "") : "";
    tick().then(() => (dialog.kind === "prompt" ? field : dialog.danger ? cancelButton : confirmButton)?.focus());
  }

  $effect(() => {
    if (open) opened(open);
  });

  function submit(e: Event) {
    e.preventDefault();
    if (!open) return;
    answerDialog(open.kind === "prompt" ? value : true);
  }

  function onfieldkeydown(e: KeyboardEvent) {
    if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) submit(e);
  }
</script>

{#if open}
  <!-- Keyed, so each dialog starts at its own size. -->
  {#key open}
    {@const multiline = open.kind === "prompt" && !!open.multiline}
    <Modal
      onclose={cancelDialog}
      width={multiline ? LARGE_WIDTH : 440}
      height={multiline ? LARGE_HEIGHT : undefined}
      resizable={open.kind === "prompt"}
      minHeight={multiline ? 220 : 140}
      label={open.title}
    >
      <form class="flex min-h-0 flex-1 flex-col gap-3 p-4" onsubmit={submit}>
        <h2 class="text-base font-semibold">{open.title}</h2>
        {#if open.message}<p class="text-sm muted">{open.message}</p>{/if}
        {#if open.kind === "prompt"}
          {#if multiline}
            <textarea
              bind:this={field}
              bind:value
              class="min-h-24 w-full flex-1 resize-none font-mono text-xs"
              placeholder={open.placeholder}
              spellcheck="false"
              onkeydown={onfieldkeydown}
            ></textarea>
          {:else}
            <input bind:this={field} bind:value class="w-full" placeholder={open.placeholder} />
          {/if}
        {/if}
        <div class="mt-auto flex justify-end gap-2">
          {#if multiline}<span class="mr-auto self-center text-xs muted">Ctrl+Enter to confirm</span>{/if}
          <button bind:this={cancelButton} type="button" class="btn" onclick={cancelDialog}>Cancel</button>
          <button bind:this={confirmButton} type="submit" class="btn {open.kind === 'confirm' && open.danger ? 'btn-destructive' : 'btn-primary'}">
            {open.confirm ?? "OK"}
          </button>
        </div>
      </form>
    </Modal>
  {/key}
{/if}
