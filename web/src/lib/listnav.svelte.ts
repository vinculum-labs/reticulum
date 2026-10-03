/**
 * Keyboard movement through a list (a menu, a picker's results): Up and Down move and wrap, Home and End jump,
 * Enter picks. Rows that can't be picked (headings, separators, disabled items) are skipped. The active row is kept
 * in view in {@code container}: by its {@code data-index}, or, in a list that only builds the rows in view, by the
 * rows' fixed height ({@code rowHeight}). Nothing is active at -1 (a menu just opened): Down then goes to the first
 * row, Up to the last.
 */
export interface ListNavOptions {
  /** Whether row i can be picked (all, when not given). */
  selectable?: (index: number) => boolean;
  /** Every row's height in px, in a list that builds only the rows in view. */
  rowHeight?: number;
}

export class ListNav {
  active = $state(0);

  constructor(
    private readonly count: () => number,
    private readonly container: () => HTMLElement | undefined = () => undefined,
    private readonly options: ListNavOptions = {},
  ) {}

  private selectable(i: number): boolean {
    return this.options.selectable?.(i) ?? true;
  }

  /** The next row that can be picked from {@code from}, going {@code step} (wrapping); -1 when there is none. */
  private next(from: number, step: 1 | -1): number {
    const n = this.count();
    for (let k = 1; k <= n; k++) {
      const i = (((from < 0 && step < 0 ? n : from) + step * k) % n + n) % n;
      if (this.selectable(i)) return i;
    }
    return -1;
  }

  /** Handles a key; returns whether it was one of the list's. */
  key(e: KeyboardEvent, pick: (index: number) => void): boolean {
    if (this.count() === 0) return false;
    let next: number;
    switch (e.key) {
      case "ArrowDown":
        next = this.next(this.active, 1);
        break;
      case "ArrowUp":
        next = this.next(this.active, -1);
        break;
      case "Home":
        next = this.next(-1, 1);
        break;
      case "End":
        next = this.next(-1, -1);
        break;
      case "Enter":
        e.preventDefault();
        if (this.active >= 0 && this.selectable(this.active)) pick(this.active);
        return true;
      default:
        return false;
    }
    e.preventDefault();
    if (next >= 0) this.set(next);
    return true;
  }

  /** Makes row {@code index} active (the first that can be picked from there, when it can't) and keeps it in view. */
  set(index: number) {
    const n = this.count();
    let i = Math.max(-1, Math.min(index, n - 1));
    if (i >= 0 && !this.selectable(i)) i = this.next(i, 1);
    this.active = i;
    if (i < 0) return;
    queueMicrotask(() => {
      const container = this.container();
      if (!container) return;
      const row = this.options.rowHeight;
      if (row) {
        const top = i * row;
        if (top < container.scrollTop) container.scrollTop = top;
        else if (top + row > container.scrollTop + container.clientHeight) container.scrollTop = top + row - container.clientHeight;
      } else {
        container.querySelector(`[data-index="${i}"]`)?.scrollIntoView({ block: "nearest" });
      }
    });
  }
}
