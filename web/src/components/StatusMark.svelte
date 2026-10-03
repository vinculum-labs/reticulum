<script lang="ts">
  import type { TreeStatus } from "../lib/status";

  /** How a tree row is doing: its icon in its state's color, with marks on the corners (gate held, problems). */
  let { status }: { status: TreeStatus } = $props();
</script>

<span class="status relative flex shrink-0" style="color: {status.color}" title={status.title} role="img" aria-label={status.title}>
  <status.icon size={14} />
  {#each status.badges as badge (badge.corner)}
    <span class="badge {badge.corner}" style="color: {badge.color}"><badge.icon size={8} /></span>
  {/each}
</span>

<style>
  /* On the sidebar's color, so a mark reads over the icon's lines. */
  .badge {
    position: absolute;
    right: -4px;
    display: flex;
    padding: 1px;
    border-radius: 50%;
    background: var(--panel);
  }
  .badge.top {
    top: -4px;
  }
  .badge.bottom {
    bottom: -4px;
  }
</style>
