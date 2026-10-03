<script lang="ts" module>
  export interface SelectOption<T extends string = string> {
    value: T;
    label: string;
    disabled?: boolean;
    title?: string;
    /** A colored dot before the label (a resource's color, say); "none": an empty ring, for "anything". */
    swatch?: string;
  }
</script>

<script lang="ts" generics="T extends string">
  import Check from "@lucide/svelte/icons/check";
  import ChevronDown from "@lucide/svelte/icons/chevron-down";
  import { ListNav } from "../../lib/listnav.svelte";
  import Popover from "./Popover.svelte";
  import { fieldId } from "./field";

  /**
   * Picks one of a few values: our own drop-down in place of the browser's select, whose list some web views open as
   * a window of their own (outside the game when it draws the editor itself). Arrow keys, Home, End and Enter move
   * and pick while it is open; a letter jumps to the next choice starting with it.
   */
  let {
    value = $bindable(),
    options,
    disabled = false,
    title,
    placeholder = "Choose…",
    class: className = "",
    onchange,
  }: {
    value: T | undefined;
    options: SelectOption<T>[];
    disabled?: boolean;
    title?: string;
    /** Shown when the value isn't one of the options. */
    placeholder?: string;
    class?: string;
    onchange?: (value: T) => void;
  } = $props();

  const id = fieldId();
  let open = $state(false);
  let trigger: HTMLButtonElement | undefined = $state();
  let list: HTMLDivElement | undefined = $state();
  let popover: Popover | undefined = $state();
  const nav = new ListNav(() => options.length, () => list);

  const current = $derived(options.find((o) => o.value === value));

  function show() {
    if (disabled) return;
    open = true;
    nav.set(Math.max(0, options.findIndex((o) => o.value === value)));
  }

  function pick(index: number) {
    const option = options[index];
    if (!option || option.disabled) return;
    popover?.close(true);
    if (option.value !== value) {
      value = option.value;
      onchange?.(option.value);
    }
  }

  /** The next enabled choice after the active one whose label starts with this letter. */
  function jump(letter: string) {
    const n = options.length;
    for (let step = 1; step <= n; step++) {
      const i = (nav.active + step) % n;
      if (!options[i].disabled && options[i].label.toLowerCase().startsWith(letter)) {
        nav.set(i);
        return;
      }
    }
  }

  function onkeydown(e: KeyboardEvent) {
    if (!open) {
      if (e.key === "ArrowDown" || e.key === "ArrowUp" || e.key === " ") {
        e.preventDefault();
        show();
      }
      return;
    }
    if (nav.key(e, pick)) return;
    if (e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) {
      e.preventDefault();
      jump(e.key.toLowerCase());
    }
  }
</script>

<button
  bind:this={trigger}
  type="button"
  {id}
  class="select flex min-w-0 items-center gap-1 text-left {className}"
  {disabled}
  {title}
  aria-haspopup="listbox"
  aria-expanded={open}
  onclick={() => (open ? popover?.close(false) : show())}
  {onkeydown}
>
  {#if current?.swatch}<span class="swatch" class:ring={current.swatch === "none"} style="--c: {current.swatch}"></span>{/if}
  <span class="trim-text min-w-0 flex-1 truncate" class:muted={!current}>{current?.label ?? placeholder}</span>
  <ChevronDown size={14} class="shrink-0 muted" />
</button>

<Popover bind:this={popover} bind:open anchor={trigger} role="listbox" label={title}>
  <div bind:this={list} class="flex flex-col overflow-y-auto py-1 text-sm" {onkeydown} role="none">
    {#each options as option, i (option.value)}
      <button
        type="button"
        class="list-row flex items-center gap-2 px-2 py-1 text-left"
        class:active={i === nav.active}
        class:muted={option.disabled}
        role="option"
        aria-selected={option.value === value}
        aria-disabled={option.disabled}
        title={option.title}
        data-index={i}
        tabindex="-1"
        onpointerenter={() => (nav.active = i)}
        onclick={() => pick(i)}
      >
        <span class="w-4 shrink-0 text-accent">{#if option.value === value}<Check size={14} />{/if}</span>
        {#if option.swatch}<span class="swatch" class:ring={option.swatch === "none"} style="--c: {option.swatch}"></span>{/if}
        <span class="trim-text min-w-0 flex-1 truncate">{option.label}</span>
      </button>
    {/each}
  </div>
</Popover>

<style>
  .swatch {
    width: 10px;
    height: 10px;
    flex-shrink: 0;
    border-radius: 50%;
    background: var(--c);
  }
  .swatch.ring {
    background: transparent;
    border: 2px solid var(--muted);
  }
  /* Looks like the other fields (inputs, the device picker). */
  .select {
    background: var(--field);
    border: 1px solid var(--line);
    border-radius: 6px;
    padding: 3px 7px;
  }
  .select:disabled {
    opacity: 0.6;
    cursor: default;
  }
</style>
