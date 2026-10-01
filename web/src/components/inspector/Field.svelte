<script lang="ts">
  import { setContext, type Snippet } from "svelte";
  import { FIELD_ID } from "../controls/field";

  /**
   * A caption, a control and a hint. Not a <label> around the control: around a composite one (the device picker, a
   * filter box with its picker) a label would turn clicks on anything inside into clicks on its first button. The
   * caption is tied to the control by id instead (TextField, NumberField and DevicePicker take it from here); with
   * {@code group} the whole is a fieldset whose legend names the controls inside.
   */
  let { label, hint, group = false, children }: { label: string; hint?: string; group?: boolean; children: Snippet } = $props();

  const id = `field-${Math.random().toString(36).slice(2, 8)}`;
  setContext(FIELD_ID, { id, taken: false });
</script>

{#if group}
  <fieldset class="flex min-w-0 flex-col gap-1">
    <legend class="field-label mb-1">{label}</legend>
    {@render children()}
    {#if hint}<span class="text-xs muted">{hint}</span>{/if}
  </fieldset>
{:else}
  <div class="flex flex-col gap-1">
    <label class="field-label" for={id}>{label}</label>
    {@render children()}
    {#if hint}<span class="text-xs muted">{hint}</span>{/if}
  </div>
{/if}
