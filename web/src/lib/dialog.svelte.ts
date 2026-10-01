/**
 * The editor's own confirm and prompt dialogs, in place of the browser's: themed, kept inside the page (the in-game
 * view has no browser chrome for them), and closed by Esc like everything else. One is open at a time; opening
 * another cancels the first.
 */

export interface ConfirmOptions {
  title: string;
  message?: string;
  /** The confirm button's label. */
  confirm?: string;
  /** Marks the confirm button as destructive, and puts focus on Cancel instead. */
  danger?: boolean;
}

export interface PromptOptions {
  title: string;
  message?: string;
  confirm?: string;
  placeholder?: string;
  value?: string;
  /** A multi-line box (Ctrl+Enter confirms) for pasted text. */
  multiline?: boolean;
}

export type OpenDialog =
  | ({ kind: "confirm"; resolve: (ok: boolean) => void } & ConfirmOptions)
  | ({ kind: "prompt"; resolve: (value: string | null) => void } & PromptOptions);

export const dialogs = $state<{ open: OpenDialog | null }>({ open: null });

/** How many modals (dialogs, the filter picker) are open; the canvas ignores its keys while any is. */
export const modals = $state({ open: 0 });

function cancelOpen() {
  const open = dialogs.open;
  if (!open) return;
  dialogs.open = null;
  if (open.kind === "confirm") open.resolve(false);
  else open.resolve(null);
}

/** Asks a yes/no question; resolves true when confirmed. */
export function confirmDialog(options: ConfirmOptions): Promise<boolean> {
  cancelOpen();
  return new Promise((resolve) => (dialogs.open = { kind: "confirm", ...options, resolve }));
}

/** Asks for text; resolves null when cancelled. */
export function promptDialog(options: PromptOptions): Promise<string | null> {
  cancelOpen();
  return new Promise((resolve) => (dialogs.open = { kind: "prompt", ...options, resolve }));
}

/** Closes the open dialog with an answer (the Dialog component's buttons). */
export function answerDialog(answer: boolean | string | null) {
  const open = dialogs.open;
  if (!open) return;
  dialogs.open = null;
  if (open.kind === "confirm") open.resolve(answer === true);
  else open.resolve(typeof answer === "string" ? answer : null);
}

export { cancelOpen as cancelDialog };
