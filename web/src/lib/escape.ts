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

/**
 * Shown inside the game (WebView2 on Windows, WKWebView on macOS) rather than in a browser tab: the game marks its
 * link with "embedded=1", remembered for the session so reloads keep it.
 */
export const embedded = (() => {
  if (typeof window === "undefined") return false;
  try {
    if (/(^#|&)embedded=1/.test(location.hash)) sessionStorage.setItem("reticulum.embedded", "1");
    return sessionStorage.getItem("reticulum.embedded") === "1";
  } catch {
    return /(^#|&)embedded=1/.test(location.hash);
  }
})();

export function installEscape(closeEditor: () => void, toggleFullscreen: () => void) {
  // Hidden (back to the game), a clicked button or switch lets go of focus: shown again, the page would hand it back, and
  // after the Esc that left it the browser draws that as keyboard focus — a ring round whatever was last clicked. A text
  // box keeps it, to go on typing.
  document.addEventListener("visibilitychange", () => {
    const active = document.activeElement;
    const typing = "textarea, select, [contenteditable], input:not([type=range], [type=checkbox], [type=radio], [type=color], [type=button])";
    if (document.visibilityState === "hidden" && active instanceof HTMLElement && !active.matches(typing)) {
      active.blur();
    }
  });
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
