/**
 * The editor's right-click menus: one open at a time, at the pointer. Anything that offers one calls {@link openMenu}
 * from its contextmenu event with what to show; the ContextMenu component draws it.
 */
import type { Component } from "svelte";

export interface MenuItem {
  label: string;
  icon?: Component<{ size?: number | string }>;
  /** A line under the label. */
  hint?: string;
  /** The key that does the same, shown at the right ("Del", "F2"). */
  shortcut?: string;
  /** A check beside it (a setting's current value, or a switch that is on). */
  checked?: boolean;
  disabled?: boolean;
  /** Red: it removes something. */
  danger?: boolean;
  run?: () => void;
  /** A submenu, opened to the side. */
  items?: MenuEntry[];
}

/** An item, a separator line ("-"), or nothing (left out: lets a list say `cond && item`). */
export type MenuEntry = MenuItem | "-" | false | null | undefined;

export const menu = $state({
  open: null as { x: number; y: number; items: MenuEntry[] } | null,
});

/** Opens a menu at the pointer for this right-click, in place of the browser's own. */
export function openMenu(event: MouseEvent, items: MenuEntry[]) {
  event.preventDefault();
  event.stopPropagation();
  menu.open = { x: event.clientX, y: event.clientY, items };
}

export function closeMenu() {
  menu.open = null;
}

/** The entries to draw: no left-out ones, and no separator at either end or two in a row. */
export function shown(items: MenuEntry[]): (MenuItem | "-")[] {
  const out: (MenuItem | "-")[] = [];
  for (const item of items) {
    if (!item) continue;
    if (item === "-" && (out.length === 0 || out[out.length - 1] === "-")) continue;
    out.push(item);
  }
  while (out[out.length - 1] === "-") out.pop();
  return out;
}
