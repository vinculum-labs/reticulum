// Whether the page shows its dark colors: it follows the system's light/dark setting, which can change while it is open.

const query = typeof matchMedia === "undefined" ? null : matchMedia("(prefers-color-scheme: dark)");

/** Read in what draws colors itself (charts), so it draws again when light and dark switch. */
export const scheme = $state({ dark: query?.matches ?? false });

query?.addEventListener("change", (e) => (scheme.dark = e.matches));
