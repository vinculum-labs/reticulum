/**
 * Keyboard movement through a list (a menu, a picker's results): Up and Down move and wrap, Home and End jump,
 * Enter picks. The active row is kept in view in {@code container}, found by its {@code data-index}.
 */
export class ListNav {
  active = $state(0);

  constructor(
    private readonly count: () => number,
    private readonly container: () => HTMLElement | undefined = () => undefined,
  ) {}

  /** Handles a key; returns whether it was one of the list's. */
  key(e: KeyboardEvent, pick: (index: number) => void): boolean {
    const n = this.count();
    if (n === 0) return false;
    let next = this.active;
    switch (e.key) {
      case "ArrowDown":
        next = (this.active + 1) % n;
        break;
      case "ArrowUp":
        next = (this.active - 1 + n) % n;
        break;
      case "Home":
        next = 0;
        break;
      case "End":
        next = n - 1;
        break;
      case "Enter":
        e.preventDefault();
        pick(this.active);
        return true;
      default:
        return false;
    }
    e.preventDefault();
    this.set(next);
    return true;
  }

  set(index: number) {
    this.active = Math.max(0, Math.min(index, this.count() - 1));
    queueMicrotask(() => this.container()?.querySelector(`[data-index="${this.active}"]`)?.scrollIntoView({ block: "nearest" }));
  }
}
