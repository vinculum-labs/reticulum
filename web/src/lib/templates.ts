/**
 * Filter templates as the export copies them ({@code {"filterTemplates": {name: filter}}}) and the import reads them
 * back (that, or a plain name → filter map).
 */

/** The export's text: every template, to paste here or in another world. */
export function exportText(filters: Record<string, string>): string {
  return JSON.stringify({ filterTemplates: filters }, null, 2);
}

/** The templates in pasted text, or nothing when it isn't templates. */
export function parseTemplates(text: string): [string, string][] {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return [];
  }
  const map = parsed && typeof parsed === "object" && "filterTemplates" in parsed ? (parsed as { filterTemplates: unknown }).filterTemplates : parsed;
  return map && typeof map === "object" ? Object.entries(map).filter((e): e is [string, string] => typeof e[1] === "string") : [];
}

/**
 * The templates in an order the server takes them: each after every one it uses (@other) among them, since each is
 * checked as it is saved. {@code existing} are the names already there and staying.
 */
export function inSavingOrder(todo: [string, string][], existing: Iterable<string>): [string, string][] {
  const ordered: [string, string][] = [];
  const known = new Set(existing);
  let left = todo;
  while (left.length) {
    const ready = left.filter(([, expression]) => [...expression.matchAll(/@([A-Za-z0-9_\-.]+)/g)].every((m) => known.has(m[1]) || !left.some(([n]) => n === m[1])));
    const batch = ready.length ? ready : left; // a loop between them: let the server say so
    for (const entry of batch) {
      ordered.push(entry);
      known.add(entry[0]);
    }
    left = left.filter((e) => !batch.includes(e));
  }
  return ordered;
}
