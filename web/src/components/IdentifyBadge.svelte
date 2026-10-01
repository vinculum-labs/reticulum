<script lang="ts">
  import { app, deviceIdentifies, setIdentifies, setIdentify } from "../lib/state.svelte";
  import type { Device } from "../lib/types";

  /**
   * A pulsing dot in each identify color while a device (or one of its faces) is lit up in the world; click turns them
   * off. Otherwise a dim ID shows while hovering its row or header (a `group`), and click lights the whole device.
   */
  let { device }: { device: Device } = $props();

  const identifies = $derived(deviceIdentifies(device));
  const hex = (color: number | undefined) => `#${(color ?? 0xffc830).toString(16).padStart(6, "0")}`;

  const canEdit = $derived(app.snapshot?.canEdit ?? false);

  function toggle(e: Event) {
    e.stopPropagation();
    if (!canEdit) return;
    if (identifies.length) setIdentifies(identifies, false);
    else setIdentify({ kind: "device", device: device.pos }, true);
  }
</script>

{#if identifies.length}
  <span
    class="nodrag flex shrink-0 cursor-pointer items-center gap-0.5 rounded px-1"
    style="height: 14px; font-size: 9px; font-weight: 600; line-height: 1; border: 1px solid {hex(identifies[0].color)}; color: {hex(identifies[0].color)}"
    role="button"
    tabindex="0"
    title={`Identified in the world: ${identifies.map((x) => (x.kind === "face" ? `${x.face} face` : "whole device")).join(", ")}. Click to turn off.`}
    onclick={toggle}
    onkeydown={(e) => (e.key === "Enter" || e.key === " ") && toggle(e)}
  >
    {#each identifies as identify, i (i)}<span class="identify-dot" style="background: {hex(identify.color)}"></span>{/each}<span class="identify-text">ID</span>
  </span>
{:else}
  <span
    class="nodrag invisible flex shrink-0 cursor-pointer items-center rounded px-1 muted group-hover:visible focus:visible"
    style="height: 14px; font-size: 9px; font-weight: 600; line-height: 1; border: 1px solid var(--line)"
    role="button"
    tabindex="0"
    title="Identify in the world"
    onclick={toggle}
    onkeydown={(e) => (e.key === "Enter" || e.key === " ") && toggle(e)}
  >
    <span class="identify-text">ID</span>
  </span>
{/if}

<style>
  .identify-dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    animation: identify-pulse 1.3s ease-in-out infinite;
  }
  /* Trim the line box to the capitals, so centering centers the letters themselves on the dots. */
  .identify-text {
    display: block;
    line-height: 1;
    text-box: trim-both cap alphabetic;
  }
  @keyframes identify-pulse {
    50% {
      opacity: 0.35;
    }
  }
</style>
