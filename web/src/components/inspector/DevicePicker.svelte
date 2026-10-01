<script lang="ts">
  import ChevronDown from "@lucide/svelte/icons/chevron-down";
  import { deviceName, matchesDevice } from "../../lib/state.svelte";
  import { ListNav } from "../../lib/listnav.svelte";
  import { posKey, type Device, type GlobalPos } from "../../lib/types";
  import Popover from "../controls/Popover.svelte";
  import { fieldId } from "../controls/field";
  import DeviceIcon from "../DeviceIcon.svelte";

  /**
   * Chooses one of the network's devices: a button showing the current one, opening a searchable list. The list
   * only builds the rows in view, so a base with thousands of devices opens as quickly as one with ten.
   */
  let {
    devices,
    value,
    placeholder = "Choose a device",
    disabled = false,
    onchange,
  }: {
    devices: Device[];
    value?: GlobalPos;
    placeholder?: string;
    disabled?: boolean;
    onchange: (device: Device) => void;
  } = $props();

  /** One row's height in px; the list is virtual, so every row is this tall. */
  const ROW = 40;
  const id = fieldId();

  let open = $state(false);
  let query = $state("");
  let trigger: HTMLButtonElement | undefined = $state();
  let list: HTMLDivElement | undefined = $state();
  let popover: Popover | undefined = $state();
  let scrollTop = $state(0);
  let viewHeight = $state(288);

  const current = $derived(value ? devices.find((d) => posKey(d.pos) === posKey(value)) : undefined);
  const matches = $derived(devices.filter((d) => matchesDevice(d, query)).sort((a, b) => deviceName(a).localeCompare(deviceName(b))));
  const nav = new ListNav(() => matches.length, () => list);

  const first = $derived(Math.max(0, Math.floor(scrollTop / ROW) - 4));
  const last = $derived(Math.min(matches.length, Math.ceil((scrollTop + viewHeight) / ROW) + 4));

  function show() {
    if (disabled) return;
    query = "";
    scrollTop = 0;
    open = true;
    nav.set(Math.max(0, matches.findIndex((d) => current && posKey(d.pos) === posKey(current.pos))));
  }

  function pick(device: Device) {
    popover?.close(true);
    if (!current || posKey(device.pos) !== posKey(current.pos)) onchange(device);
  }

  function onkeydown(e: KeyboardEvent) {
    nav.key(e, (i) => matches[i] && pick(matches[i]));
  }
</script>

<button
  bind:this={trigger}
  type="button"
  {id}
  class="flex w-full items-center gap-2 rounded-md border px-2 py-1 text-left"
  style="background: var(--field); border-color: var(--line)"
  {disabled}
  onclick={() => (open ? popover?.close(false) : show())}
  aria-haspopup="listbox"
  aria-expanded={open}
>
  <DeviceIcon item={current?.icon} size={18} />
  <span class="min-w-0 flex-1 truncate" class:muted={!current}>{current ? deviceName(current) : placeholder}</span>
  <ChevronDown size={14} class="muted" />
</button>

<Popover bind:this={popover} bind:open anchor={trigger} width={260} label="Devices">
  <!-- svelte-ignore a11y_autofocus -->
  <input
    bind:value={query}
    class="m-1 w-[calc(100%-0.5rem)]"
    placeholder="Search by name, block or x y z"
    autofocus
    oninput={() => {
      nav.set(0);
      if (list) list.scrollTop = 0;
    }}
    {onkeydown}
  />
  <div
    bind:this={list}
    bind:clientHeight={viewHeight}
    class="min-h-0 flex-1 overflow-y-auto"
    style="max-height: 18rem"
    role="listbox"
    aria-label="Devices"
    onscroll={() => (scrollTop = list?.scrollTop ?? 0)}
  >
    {#if matches.length === 0}
      <p class="px-2 py-1 text-sm muted">No devices match.</p>
    {:else}
      <div class="relative" style="height: {matches.length * ROW}px">
        <div class="absolute inset-x-0" style="top: {first * ROW}px">
          {#each matches.slice(first, last) as d, j (posKey(d.pos))}
            {@const i = first + j}
            <button
              type="button"
              role="option"
              aria-selected={i === nav.active}
              data-index={i}
              class="list-row flex w-full items-center gap-2 px-2 text-left"
              class:active={i === nav.active}
              style="height: {ROW}px"
              tabindex="-1"
              onpointerenter={() => (nav.active = i)}
              onclick={() => pick(d)}
            >
              <DeviceIcon item={d.icon} size={18} />
              <span class="min-w-0 flex-1">
                <span class="block truncate">{deviceName(d)}</span>
                <span class="block truncate text-xs muted">{d.pos.pos.join(", ")}{d.state !== "active" ? ` · ${d.state}` : d.outOfRange ? " · out of range" : ""}</span>
              </span>
            </button>
          {/each}
        </div>
      </div>
    {/if}
  </div>
</Popover>
