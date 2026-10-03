/**
 * Whether the side panel shows its explanations: field hints and the notes about how things work. Off, they stay out of
 * the way (a field's hint is still its label's tooltip); the ? in a panel's header turns them on, remembered per browser.
 */
export const help = $state({ shown: stored() });

function stored(): boolean {
  try {
    return localStorage.getItem("reticulum.help") === "1";
  } catch {
    return false;
  }
}

export function toggleHelp(): void {
  help.shown = !help.shown;
  try {
    localStorage.setItem("reticulum.help", help.shown ? "1" : "0");
  } catch {
    // Private windows and blocked storage: the choice lasts until the page closes.
  }
}
