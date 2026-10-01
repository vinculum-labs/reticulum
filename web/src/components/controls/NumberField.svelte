<script lang="ts">
  import { clampNumber, fieldId as takeFieldId } from "./field";

  /**
   * A number box with the same saving rules as TextField. Typed numbers are kept within {@code min}..{@code max}
   * and whole unless {@code integer} is false; the box then shows what was saved. {@code scale} shows the stored
   * value multiplied (a rate stored per second, shown per tick: 1/20).
   */
  let {
    value,
    oncommit,
    min,
    max,
    step,
    integer = true,
    scale = 1,
    optional = false,
    placeholder,
    disabled = false,
    width = "sm",
    title,
    id,
  }: {
    value: number | null | undefined;
    oncommit: (value: number | undefined) => void;
    min?: number;
    max?: number;
    step?: number;
    /** Whole numbers only (of the stored value). */
    integer?: boolean;
    scale?: number;
    /** Blank saves "no value" (undefined) instead of going back. */
    optional?: boolean;
    placeholder?: string;
    disabled?: boolean;
    width?: "xs" | "sm" | "md" | "lg";
    title?: string;
    id?: string;
  } = $props();

  const WIDTH = { xs: "w-14", sm: "w-20", md: "w-24", lg: "w-32" };
  const fieldId = takeFieldId();
  let element: HTMLInputElement | undefined = $state();
  let draft = $state("");
  let focused = false;

  function shown(stored: number | null | undefined): string {
    if (stored === null || stored === undefined) return "";
    return String(Number((stored * scale).toFixed(4)));
  }

  $effect(() => {
    const saved = shown(value);
    if (!focused) draft = saved;
  });

  function commit() {
    const text = draft.trim();
    if (text === "") {
      if (optional && value !== undefined && value !== null) oncommit(undefined);
      draft = shown(value);
      return;
    }
    const entered = Number(text);
    if (!Number.isFinite(entered)) {
      draft = shown(value);
      return;
    }
    const stored = clampNumber(entered / scale, { min, max, integer });
    draft = shown(stored);
    if (stored !== value) oncommit(stored);
  }

  function onkeydown(e: KeyboardEvent) {
    if (e.key === "Enter") {
      e.preventDefault();
      element?.blur();
    } else if (e.key === "Escape") {
      e.preventDefault();
      draft = shown(value);
      element?.blur();
    }
  }
</script>

<input
  bind:this={element}
  bind:value={draft}
  type="number"
  id={id ?? fieldId}
  class="{WIDTH[width]} shrink-0"
  min={min !== undefined ? min * scale : undefined}
  max={max !== undefined ? max * scale : undefined}
  step={step ?? (integer ? scale : "any")}
  {placeholder}
  {disabled}
  {title}
  onfocus={() => (focused = true)}
  onblur={() => {
    focused = false;
    commit();
  }}
  {onkeydown}
/>
