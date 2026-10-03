/** What a filter can name: every item, fluid and chemical, with names and tags, served by the game client. */

export type CatalogKind = "item" | "fluid" | "chemical";

/** The picker's list for a resource; resources without one (energy, redstone) use items. */
export function catalogKind(resource: string | null | undefined): CatalogKind {
  return resource === "fluid" || resource === "chemical" ? resource : "item";
}

export interface CatalogEntry {
  id: string;
  name: string;
  tags: string[];
}

/** The lists by kind, and every block that has an item (by block id): the kinds of block a blueprint's slot takes. */
export type Catalog = Record<CatalogKind, CatalogEntry[]> & { block?: CatalogEntry[] };

export const CATALOG_KIND_LABEL: Record<CatalogKind, string> = { item: "Items", fluid: "Fluids", chemical: "Chemicals" };

let loading: Promise<Catalog> | null = null;

/** Loaded once per page; the game's names and tags don't change while it runs (bar a /reload). */
export function loadCatalog(): Promise<Catalog> {
  loading ??= fetch("/catalog.json")
    .then((r) => {
      if (!r.ok) throw new Error(`catalog: ${r.status}`);
      return r.json() as Promise<Catalog>;
    })
    .catch((e) => {
      loading = null;
      throw e;
    });
  return loading;
}

export function iconUrl(kind: CatalogKind, id: string): string {
  return `/icon/${kind}/${id.replace(":", "/")}.png`;
}

/**
 * Whether an entry matches a search: every word must match. "@mod" matches the namespace, "#tag" any of its tags,
 * anything else the name or id.
 */
export function matchesSearch(entry: CatalogEntry, words: string[]): boolean {
  return words.every((word) => {
    if (word.startsWith("@")) return entry.id.slice(0, entry.id.indexOf(":")).startsWith(word.slice(1));
    if (word.startsWith("#")) return entry.tags.some((t) => t.includes(word.slice(1)));
    return entry.name.toLowerCase().includes(word) || entry.id.includes(word);
  });
}

// ---------------------------------------------------------------- filter text

/** Whether an expression has an "|" outside brackets and quotes (so adding "& !x" needs brackets around it). */
function hasTopLevelOr(text: string): boolean {
  let depth = 0;
  let quote = false;
  let regex = false;
  for (const c of text) {
    if (quote) quote = c !== '"';
    else if (regex) regex = c !== "/";
    else if (c === '"') quote = true;
    else if (c === "(") depth++;
    else if (c === ")") depth--;
    else if (c === "|" && depth === 0) return true;
  }
  return false;
}

/** Adds a term as another thing that may match ("a | term"). */
export function withAlternative(text: string, term: string): string {
  const t = text.trim();
  return !t || t === "*" ? term : `${t} | ${term}`;
}

/** Adds a term as something that must not match ("a !term", bracketing "a | b" first). */
export function withExclusion(text: string, term: string): string {
  const t = text.trim();
  if (!t || t === "*") return `!${term}`;
  return hasTopLevelOr(t) ? `(${t}) !${term}` : `${t} !${term}`;
}

export const itemTerm = (id: string) => id;
export const tagTerm = (tag: string) => `#${tag}`;
export const modTerm = (id: string) => `mod:${id.slice(0, id.indexOf(":"))}`;
