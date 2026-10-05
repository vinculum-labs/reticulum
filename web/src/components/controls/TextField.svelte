<script lang="ts">
  import { tick } from "svelte";
  import { fieldId as takeFieldId } from "./field";
  import Popover from "./Popover.svelte";

  /**
   * A text box that saves when you leave it or press Enter, and only when the text really changed. Esc puts back the
   * saved text. What it shows follows the saved value, so a refused or ignored edit doesn't leave the box saying
   * something that wasn't saved.
   */
  let {
    value,
    oncommit,
    placeholder,
    maxlength,
    disabled = false,
    required = false,
    trim = true,
    mono = false,
    invalid = false,
    suggestions,
    autofocus = false,
    id,
    class: className = "",
    title,
    oninput,
    onfinish,
  }: {
    value: string | null | undefined;
    oncommit: (value: string) => void;
    placeholder?: string;
    /** The most characters it takes (the server's limit, so a longer name isn't refused whole). */
    maxlength?: number;
    disabled?: boolean;
    /** Blank isn't saved: the box goes back to the saved text. */
    required?: boolean;
    trim?: boolean;
    mono?: boolean;
    /** Marks the text as wrong (a red border); the message is the caller's to show. */
    invalid?: boolean;
    /** Offered while typing: those containing the text so far, in a list under the box. */
    suggestions?: string[];
    autofocus?: boolean;
    id?: string;
    class?: string;
    title?: string;
    /** Every keystroke, for live checking. */
    oninput?: (text: string) => void;
    /** After the box saved or gave up (Enter, Esc or leaving it): for boxes shown only while editing. */
    onfinish?: () => void;
  } = $props();

  const fieldId = takeFieldId();
  let element: HTMLInputElement | undefined = $state();
  let draft = $state("");
  let focused = $state(false);

  // The suggestions: our own list rather than a datalist, whose list some web views open as a window of their own
  // (outside the game when it draws the editor itself).
  let suggesting = $state(false);
  /** The suggestion picked with the arrow keys; -1 for none (Enter then saves what was typed). */
  let active = $state(-1);
  let list: HTMLDivElement | undefined = $state();
  const matches = $derived.by(() => {
    const text = draft.trim().toLowerCase();
    return (suggestions ?? []).filter((s) => s.toLowerCase() !== text && s.toLowerCase().includes(text)).slice(0, 50);
  });
  /** The suggestion just chosen: the list stays shut on it (it would offer longer ones containing it) until typing. */
  let chosen: string | null = null;
  $effect(() => {
    // Open while typing in the box with something to offer; a new list starts with nothing picked.
    suggesting = focused && matches.length > 0 && draft !== chosen;
    active = -1;
  });

  function choose(suggestion: string) {
    chosen = suggestion;
    draft = suggestion;
    suggesting = false;
    commit();
  }

  function move(step: number) {
    const n = matches.length;
    active = active < 0 ? (step > 0 ? 0 : n - 1) : (active + step + n) % n;
    list?.querySelector(`[data-index="${active}"]`)?.scrollIntoView({ block: "nearest" });
  }
  /** The text last handed to oncommit, so Enter followed by the blur it causes saves once. */
  let sent: string | null = null;

  // Follow the saved value whenever it changes, except under the user's fingers.
  $effect(() => {
    const saved = value ?? "";
    if (!focused) draft = saved;
    sent = null;
  });

  $effect(() => {
    const box = element;
    if (autofocus && box) {
      // After the saved text is in the box (filling it moves the caret to the end), so all of it is selected: typing
      // replaces it.
      tick().then(() => {
        box.focus();
        box.select();
      });
    }
  });

  function commit() {
    const text = trim ? draft.trim() : draft;
    const saved = value ?? "";
    if ((required && text === "") || text === saved || text === sent) {
      draft = saved;
      return;
    }
    sent = text;
    draft = text;
    oncommit(text);
  }

  function onkeydown(e: KeyboardEvent) {
    if (suggesting && (e.key === "ArrowDown" || e.key === "ArrowUp")) {
      e.preventDefault();
      move(e.key === "ArrowDown" ? 1 : -1);
      return;
    }
    if (e.key === "Enter" && suggesting && active >= 0) {
      e.preventDefault();
      choose(matches[active]);
      return;
    }
    if (e.key === "Enter") {
      e.preventDefault();
      commit();
      element?.blur();
    } else if (e.key === "Escape") {
      // Handled here, so the page's Esc doesn't also act on it.
      e.preventDefault();
      draft = value ?? "";
      sent = draft;
      element?.blur();
    }
  }
</script>

<input
  bind:this={element}
  bind:value={draft}
  id={id ?? fieldId}
  class="{className} {mono ? 'font-mono' : ''}"
  style={invalid ? "border-color: var(--bad)" : ""}
  {placeholder}
  {maxlength}
  {disabled}
  {title}
  spellcheck={mono ? "false" : undefined}
  onfocus={() => (focused = true)}
  onblur={() => {
    focused = false;
    commit();
    onfinish?.();
  }}
  oninput={() => oninput?.(draft)}
  {onkeydown}
/>
{#if suggestions}
  <Popover bind:open={suggesting} anchor={element} role="listbox" label="Suggestions">
    <div bind:this={list} class="flex flex-col overflow-y-auto py-1 text-sm" role="none">
      {#each matches as suggestion, i (suggestion)}
        <!-- Pressing keeps the keyboard in the box, so it doesn't save the half-typed text before the pick. Not
             shrinking: clipped (truncate) rows in a column too short for them were squashed to slivers. -->
        <button
          type="button"
          class="list-row shrink-0 truncate px-2 py-1 text-left"
          class:active={i === active}
          role="option"
          aria-selected={i === active}
          data-index={i}
          tabindex="-1"
          onpointerdown={(e) => e.preventDefault()}
          onpointerenter={() => (active = i)}
          onclick={() => choose(suggestion)}
        >
          {suggestion}
        </button>
      {/each}
    </div>
  </Popover>
{/if}
