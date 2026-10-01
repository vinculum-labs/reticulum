<script lang="ts">
  import { fieldId as takeFieldId } from "./field";

  /**
   * A text box that saves when you leave it or press Enter, and only when the text really changed. Esc puts back the
   * saved text. What it shows follows the saved value, so a refused or ignored edit doesn't leave the box saying
   * something that wasn't saved.
   */
  let {
    value,
    oncommit,
    placeholder,
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
    disabled?: boolean;
    /** Blank isn't saved: the box goes back to the saved text. */
    required?: boolean;
    trim?: boolean;
    mono?: boolean;
    /** Marks the text as wrong (a red border); the message is the caller's to show. */
    invalid?: boolean;
    /** Offered while typing (a datalist). */
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
  const listId = `suggest-${Math.random().toString(36).slice(2, 8)}`;
  let element: HTMLInputElement | undefined = $state();
  let draft = $state("");
  let focused = false;
  /** The text last handed to oncommit, so Enter followed by the blur it causes saves once. */
  let sent: string | null = null;

  // Follow the saved value whenever it changes, except under the user's fingers.
  $effect(() => {
    const saved = value ?? "";
    if (!focused) draft = saved;
    sent = null;
  });

  $effect(() => {
    if (autofocus && element) {
      element.focus();
      element.select();
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
  {disabled}
  {title}
  spellcheck={mono ? "false" : undefined}
  list={suggestions ? listId : undefined}
  onfocus={() => (focused = true)}
  onblur={() => {
    focused = false;
    commit();
    onfinish?.();
  }}
  oninput={() => oninput?.(draft)}
  {onkeydown}
/>
{#if suggestions}<datalist id={listId}>{#each suggestions as option (option)}<option value={option}></option>{/each}</datalist>{/if}
