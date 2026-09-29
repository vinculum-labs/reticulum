/**
 * Merges a fresh copy of some data into the copy already held, keeping every part that didn't change as the very
 * same object. Anything that compares by identity (Svelte's derived values, the canvas's node reuse) then sees only
 * what changed, instead of treating a whole new network snapshot as all-new after every edit.
 *
 * Arrays of objects with an `id` are matched by id (so a new item or a reorder doesn't make every later item look
 * changed); other arrays by position.
 */
export function share<T>(previous: T, next: T): T {
  if (previous === next) return previous;
  if (Array.isArray(previous) && Array.isArray(next)) return shareArray(previous, next) as T;
  if (isPlainObject(previous) && isPlainObject(next)) return shareObject(previous, next) as T;
  return next;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function idOf(value: unknown): unknown {
  return isPlainObject(value) ? value.id : undefined;
}

function shareArray(previous: unknown[], next: unknown[]): unknown[] {
  const byId = new Map<unknown, unknown>();
  for (const item of previous) {
    const id = idOf(item);
    if (id !== undefined) byId.set(id, item);
  }
  let same = previous.length === next.length;
  const result = next.map((item, i) => {
    const id = idOf(item);
    const old = id !== undefined ? byId.get(id) : previous[i];
    const shared = old === undefined ? item : share(old, item);
    if (shared !== previous[i]) same = false;
    return shared;
  });
  return same ? previous : result;
}

function shareObject(previous: Record<string, unknown>, next: Record<string, unknown>): Record<string, unknown> {
  const keys = Object.keys(next);
  let same = keys.length === Object.keys(previous).length;
  const result: Record<string, unknown> = {};
  for (const key of keys) {
    const shared = key in previous ? share(previous[key], next[key]) : next[key];
    if (shared !== previous[key]) same = false;
    result[key] = shared;
  }
  return same ? previous : result;
}
