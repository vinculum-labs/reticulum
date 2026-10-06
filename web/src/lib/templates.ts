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
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return [];
  if ("filterTemplates" in parsed) parsed = (parsed as { filterTemplates: unknown }).filterTemplates;
  // A plain name → filter map, else not a list, nor something else copied as JSON (a sheet has its id and name).
  else if ("id" in parsed && "name" in parsed) return [];
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return [];
  return Object.entries(parsed).filter((e): e is [string, string] => typeof e[1] === "string");
}

/**
 * The templates in an order the server takes them: each after every one it uses (@other) among them, since each is
 * checked as it is saved. {@code existing} are the names already there and staying.
 */
/** The templates a filter names: @name (up to a space, bracket or operator) or @"a name" (with \" inside). */
export function references(expression: string): string[] {
  return [...expression.matchAll(/@(?:"((?:[^"\\]|\\.)*)"|([^\s()&|!"]+))/g)].map((m) => (m[1] !== undefined ? m[1].replace(/\\"/g, '"') : m[2]));
}

export function inSavingOrder(todo: [string, string][], existing: Iterable<string>): [string, string][] {
  const ordered: [string, string][] = [];
  const known = new Set(existing);
  let left = todo;
  while (left.length) {
    const ready = left.filter(([, expression]) => references(expression).every((name) => known.has(name) || !left.some(([n]) => n === name)));
    const batch = ready.length ? ready : left; // a loop between them: let the server say so
    for (const entry of batch) {
      ordered.push(entry);
      known.add(entry[0]);
    }
    left = left.filter((e) => !batch.includes(e));
  }
  return ordered;
}
