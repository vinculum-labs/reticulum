<script lang="ts">
  import { deviceIdentifies, setIdentify } from "../lib/state.svelte";
  import type { Device } from "../lib/types";

  /** A pulsing dot in each identify color while a device (or one of its faces) is lit up in the world; click turns them off. */
  let { device }: { device: Device } = $props();

  const identifies = $derived(deviceIdentifies(device));
  const hex = (color: number | undefined) => `#${(color ?? 0xffc830).toString(16).padStart(6, "0")}`;

  function off(e: Event) {
    e.stopPropagation();
    for (const identify of identifies) setIdentify(identify, false);
  }
</script>

{#if identifies.length}
  <span
    class="nodrag flex shrink-0 cursor-pointer items-center gap-0.5 rounded px-1"
    style="height: 14px; font-size: 9px; font-weight: 600; line-height: 1; border: 1px solid {hex(identifies[0].color)}; color: {hex(identifies[0].color)}"
    role="button"
    tabindex="0"
    title={`Identified in the world: ${identifies.map((x) => (x.kind === "face" ? `${x.face} face` : "whole device")).join(", ")}. Click to turn off.`}
    onclick={off}
    onkeydown={(e) => (e.key === "Enter" || e.key === " ") && off(e)}
  >
    {#each identifies as identify, i (i)}<span class="identify-dot" style="background: {hex(identify.color)}"></span>{/each}<span class="identify-text">ID</span>
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
