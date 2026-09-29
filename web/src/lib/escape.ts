/**
 * Esc, the same everywhere: it closes the innermost open thing (a dialog, a menu, a dropdown), else leaves a focused
 * text box, else — only when the editor is shown inside the game — closes the editor.
 */

const stack: (() => void)[] = [];

/** Registers something Esc should close while it is open; returns the function that unregisters it. */
export function onEscape(close: () => void): () => void {
  stack.push(close);
  return () => {
    const i = stack.lastIndexOf(close);
    if (i >= 0) stack.splice(i, 1);
  };
}

/** Shown inside the game (Edge WebView2) rather than in a browser tab. */
export const embedded = typeof window !== "undefined" && !!(window as unknown as { chrome?: { webview?: unknown } }).chrome?.webview;

export function installEscape(closeEditor: () => void, toggleFullscreen: () => void) {
  window.addEventListener("keydown", (e) => {
    // Inside the game the page has the keyboard, so the game's own F11 (fullscreen) is passed on.
    if (e.key === "F11" && embedded) {
      e.preventDefault();
      toggleFullscreen();
      return;
    }
    // A field that handles Esc itself (e.g. cancelling a rename) marks it handled.
    if (e.key !== "Escape" || e.defaultPrevented) return;
    const top = stack.at(-1);
    if (top) {
      e.preventDefault();
      top();
      return;
    }
    const active = document.activeElement;
    if (active instanceof HTMLElement && active.matches("input, textarea, select, [contenteditable]")) {
      e.preventDefault();
      active.blur();
      return;
    }
    if (embedded) {
      e.preventDefault();
      closeEditor();
    }
  });
}
