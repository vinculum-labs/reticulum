<script lang="ts">
  import { app } from "../../lib/state.svelte";
  import { STATUS_LABEL, formatAgo, formatAverage, formatRate } from "../../lib/kinds";

  /** What a node or wire is moving now, why not if it isn't, and its last 5 minutes. Signal nodes show their value. */
  let { id, signal = false }: { id: string; signal?: boolean } = $props();
</script>

<div class="box text-sm" role="status">
  {#if signal}
    Output <span class="font-medium tabular-nums" style="color: {(app.signals[id] ?? 0) > 0 ? 'var(--c-signal)' : 'var(--muted)'}">{app.signals[id] ?? 0}</span>
    <div class="text-xs muted">Updated once a second. Above 0 counts as on.</div>
  {:else}
    {#if (app.rates[id] ?? 0) > 0}
      Moving {formatRate(app.rates[id], null, app.rateUnit)}
    {:else if app.status[id]}
      <span class="text-warn">Idle: {STATUS_LABEL[app.status[id]] ?? app.status[id]}</span>
    {:else}
      Idle
    {/if}
    <div class="text-xs muted">
      Average {formatAverage(app.fiveMinutes[id] ?? 0, null, app.rateUnit)} over 5 min ·
      {app.ticksSinceMoved[id] !== undefined ? `last moved ${formatAgo(app.ticksSinceMoved[id])}` : "never moved"}
    </div>
  {/if}
</div>
