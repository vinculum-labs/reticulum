<script lang="ts">
  import { tick } from "svelte";
  import { answerDialog, cancelDialog, dialogs } from "../lib/dialog.svelte";
  import { onEscape } from "../lib/escape";

  /**
   * The one dialog the editor shows (lib/dialog.svelte.ts opens it). A native <dialog> shown modally, so the page
   * behind it is inert and Tab stays inside.
   */
  let element: HTMLDialogElement | undefined = $state();
  let field: HTMLInputElement | HTMLTextAreaElement | undefined = $state();
  let cancelButton: HTMLButtonElement | undefined = $state();
  let confirmButton: HTMLButtonElement | undefined = $state();
  let value = $state("");

  const open = $derived(dialogs.open);

  $effect(() => {
    if (!open || !element) return;
    value = open.kind === "prompt" ? (open.value ?? "") : "";
    if (!element.open) element.showModal();
    // A destructive question starts on Cancel, so Enter doesn't confirm it by accident.
    tick().then(() => (open.kind === "prompt" ? field : open.danger ? cancelButton : confirmButton)?.focus());
    const release = onEscape(cancelDialog);
    return () => {
      release();
      if (element?.open) element.close();
    };
  });

  function submit(e: Event) {
    e.preventDefault();
    if (!open) return;
    answerDialog(open.kind === "prompt" ? value : true);
  }

  function onfieldkeydown(e: KeyboardEvent) {
    if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) submit(e);
  }

  /** Whether the current press began on the backdrop (the dialog element itself, outside its box). */
  let pressedBackdrop = false;

  function onpointerdown(e: PointerEvent) {
    pressedBackdrop = e.target === element;
  }

  /**
   * A click on the backdrop cancels, but only when the press began there too: a drag that starts inside (resizing the
   * text box, selecting text) and ends outside is also reported as a click on the dialog element.
   */
  function onclick(e: MouseEvent) {
    if (pressedBackdrop && e.target === element) cancelDialog();
    pressedBackdrop = false;
  }
</script>

<!-- Esc goes through the page's Esc stack; the dialog's own cancel is only a fallback. -->
<dialog
  bind:this={element}
  class="m-auto w-[min(440px,calc(100vw-32px))] rounded-lg border p-0"
  style="background: var(--panel); color: var(--ink); border-color: var(--line); box-shadow: 0 12px 40px rgb(0 0 0 / 0.35)"
  oncancel={(e) => (e.preventDefault(), cancelDialog())}
  {onpointerdown}
  {onclick}
>
  {#if open}
    <form class="flex flex-col gap-3 p-4" onsubmit={submit}>
      <h2 class="text-base font-semibold">{open.title}</h2>
      {#if open.message}<p class="text-sm muted">{open.message}</p>{/if}
      {#if open.kind === "prompt"}
        {#if open.multiline}
          <textarea
            bind:this={field}
            bind:value
            class="h-40 w-full font-mono text-xs"
            placeholder={open.placeholder}
            spellcheck="false"
            onkeydown={onfieldkeydown}
          ></textarea>
        {:else}
          <input bind:this={field} bind:value class="w-full" placeholder={open.placeholder} />
        {/if}
      {/if}
      <div class="flex justify-end gap-2">
        {#if open.kind === "prompt" && open.multiline}<span class="mr-auto self-center text-xs muted">Ctrl+Enter to confirm</span>{/if}
        <button bind:this={cancelButton} type="button" class="btn" onclick={cancelDialog}>Cancel</button>
        <button bind:this={confirmButton} type="submit" class="btn {open.kind === 'confirm' && open.danger ? 'btn-destructive' : 'btn-primary'}">
          {open.confirm ?? "OK"}
        </button>
      </div>
    </form>
  {/if}
</dialog>

<style>
  dialog::backdrop {
    background: rgb(0 0 0 / 0.45);
  }
</style>
