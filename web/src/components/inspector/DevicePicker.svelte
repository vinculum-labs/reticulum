<script lang="ts">
  import { onEscape } from "../../lib/escape";
  import { deviceName } from "../../lib/state.svelte";
  import { posKey, type Device, type GlobalPos } from "../../lib/types";
  import DeviceIcon from "../DeviceIcon.svelte";

  let {
    devices,
    value,
    disabled = false,
    onchange,
  }: { devices: Device[]; value: GlobalPos; disabled?: boolean; onchange: (device: Device) => void } = $props();

  const LIMIT = 100;

  let open = $state(false);
  // Esc closes the list while it is open, wherever the focus is.
  $effect(() => {
    if (open) return onEscape(() => (open = false));
  });
  let query = $state("");
  let active = $state(0);
  let root: HTMLDivElement | undefined = $state();
  let search: HTMLInputElement | undefined = $state();

  const current = $derived(devices.find((d) => posKey(d.pos) === posKey(value)));

  /** Every word must appear in the name, default name, block id or coordinates. */
  const matches = $derived.by(() => {
    const words = query.toLowerCase().split(/\s+/).filter(Boolean);
    const found = devices.filter((d) => {
      const text = `${deviceName(d)} ${d.defaultName} ${d.block} ${d.pos.pos.join(" ")}`.toLowerCase();
      return words.every((w) => text.includes(w));
    });
    return found.sort((a, b) => deviceName(a).localeCompare(deviceName(b)));
  });

  function show() {
    if (disabled) return;
    open = true;
    query = "";
    active = Math.max(0, matches.findIndex((d) => posKey(d.pos) === posKey(value)));
    queueMicrotask(() => search?.focus());
  }

  function pick(device: Device) {
    open = false;
    if (posKey(device.pos) !== posKey(value)) onchange(device);
  }

  function onkeydown(e: KeyboardEvent) {
    const shown = Math.min(matches.length, LIMIT);
    if (e.key === "ArrowDown") {
      e.preventDefault();
      active = Math.min(shown - 1, active + 1);
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      active = Math.max(0, active - 1);
    } else if (e.key === "Enter") {
      e.preventDefault();
      if (matches[active]) pick(matches[active]);
    } else if (e.key === "Escape") {
      e.preventDefault();
      open = false;
    }
  }

  function onfocusout(e: FocusEvent) {
    if (!root?.contains(e.relatedTarget as Node | null)) open = false;
  }
</script>

<div class="relative" bind:this={root} {onfocusout}>
  <button
    type="button"
    class="flex w-full items-center gap-2 rounded-md border px-2 py-1 text-left"
    style="background: var(--field); border-color: var(--line)"
    {disabled}
    onclick={() => (open ? (open = false) : show())}
    aria-haspopup="listbox"
    aria-expanded={open}
  >
    <DeviceIcon item={current?.icon} size={18} />
    <span class="min-w-0 flex-1 truncate">{current ? deviceName(current) : "Choose a device"}</span>
    <span class="muted">▾</span>
  </button>

  {#if open}
    <div class="absolute right-0 left-0 z-30 mt-1 rounded-md border shadow-lg" style="background: var(--panel); border-color: var(--line)">
      <input
        bind:this={search}
        bind:value={query}
        class="m-1 w-[calc(100%-0.5rem)]"
        placeholder="Search by name, block or x y z"
        oninput={() => (active = 0)}
        {onkeydown}
        aria-controls="device-picker-list"
      />
      <ul id="device-picker-list" role="listbox" class="max-h-72 overflow-y-auto py-1">
        {#each matches.slice(0, LIMIT) as d, i (posKey(d.pos))}
          <li role="option" aria-selected={i === active}>
            <button
              type="button"
              class="flex w-full items-center gap-2 px-2 py-1 text-left"
              style={i === active ? "background: var(--panel-2)" : ""}
              onmouseenter={() => (active = i)}
              onclick={() => pick(d)}
            >
              <DeviceIcon item={d.icon} size={18} />
              <span class="min-w-0 flex-1">
                <span class="block truncate">{deviceName(d)}</span>
                <span class="block truncate text-xs muted">{d.pos.pos.join(", ")}{d.state !== "active" ? ` · ${d.state}` : ""}</span>
              </span>
            </button>
          </li>
        {:else}
          <li class="px-2 py-1 text-sm muted">No devices match.</li>
        {/each}
        {#if matches.length > LIMIT}
          <li class="px-2 py-1 text-xs muted">{matches.length - LIMIT} more; keep typing to narrow it down.</li>
        {/if}
      </ul>
    </div>
  {/if}
</div>
