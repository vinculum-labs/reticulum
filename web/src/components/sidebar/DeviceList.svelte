<script lang="ts">
  import { unreachable } from "../../lib/status";
  import Pencil from "@lucide/svelte/icons/pencil";
  import Plus from "@lucide/svelte/icons/plus";
  import { help } from "../../lib/help.svelte";
  import { identifyItem, openMenu } from "../../lib/menu.svelte";
  import { chartableResources, deviceChart, deviceChartLabel, toggleDeviceChart, type DeviceChart } from "../../lib/charts";
  import ChartLine from "@lucide/svelte/icons/chart-line";
  import { app, deviceName, deviceUses, matchesDevice, openDevice, placeDevice } from "../../lib/state.svelte";
  import { posKey, type Device } from "../../lib/types";
  import Segmented from "../controls/Segmented.svelte";
  import DeviceIcon from "../DeviceIcon.svelte";
  import IdentifyBadge from "../IdentifyBadge.svelte";

  /** The network's devices: searched, all or only those not placed yet, dragged onto the canvas to add one. */

  const snapshot = $derived(app.snapshot!);
  const canEdit = $derived(snapshot.canEdit);

  let search = $state("");
  /** Show every device, or only those no sheet uses yet (remembered per browser). */
  let filter = $state<"all" | "unused">(storedFilter());
  const uses = $derived(deviceUses(snapshot));
  /** Devices not placed yet: no device node on any sheet (reading one with a sensor doesn't count). */
  const placed = (key: string) => (uses.get(key) ?? []).some((u) => u.home);
  const unusedCount = $derived(snapshot.devices.filter((d) => !placed(posKey(d.pos))).length);

  const devices = $derived(
    snapshot.devices
      .filter((d) => matchesDevice(d, search) && (filter === "all" || !placed(posKey(d.pos))))
      .sort((a, b) => deviceName(a).localeCompare(deviceName(b))),
  );

  function storedFilter(): "all" | "unused" {
    try {
      return localStorage.getItem("reticulum.deviceFilter") === "unused" ? "unused" : "all";
    } catch {
      return "all";
    }
  }

  function setFilter(next: "all" | "unused") {
    filter = next;
    try {
      localStorage.setItem("reticulum.deviceFilter", next);
    } catch {
      // Private windows and blocked storage: the choice lasts until the page closes.
    }
  }

  function drag(e: DragEvent, key: string) {
    e.dataTransfer?.setData("application/x-reticulum-device", key);
    e.dataTransfer!.effectAllowed = "copy";
  }

  function deviceMenu(e: MouseEvent, d: Device) {
    const open = snapshot.sheets.find((s) => s.id === app.sheetId);
    const homed = placed(posKey(d.pos));
    const canPlace = canEdit && d.state === "active" && !!open && !app.instance && (open.blueprint || !homed);
    openMenu(e, [
      identifyItem(d.pos),
      { label: "Names…", icon: Pencil, run: () => openDevice(d) },
      canEdit && {
        label: open?.blueprint ? "Add a slot to the open blueprint" : "Add to the open sheet",
        icon: Plus,
        disabled: !canPlace,
        hint: !canPlace && homed && !open?.blueprint ? "It already has a node" : undefined,
        run: () => placeDevice(d),
      },
      chartableResources(d).length > 0 && {
        label: "Chart",
        icon: ChartLine,
        items: [
          ...(["level", "through"] as DeviceChart[]).flatMap((kind) =>
            chartableResources(d).map((resource) => ({
              label: deviceChartLabel(kind, resource),
              checked: !!deviceChart(d, kind, resource),
              disabled: !canEdit || (!deviceChart(d, kind, resource) && (snapshot.charts?.length ?? 0) >= 16),
              run: () => toggleDeviceChart(d, kind, resource),
            })),
          ),
          "-" as const,
          { label: "Open charts", run: () => (app.panel = "charts") },
        ],
      },
    ]);
  }
</script>

<section class="border-t border-line p-3">
  <div class="mb-2 flex items-center justify-between gap-2">
    <h2 class="panel-title">Devices</h2>
    <Segmented
      label="Which devices"
      size="xs"
      value={filter}
      options={[
        { value: "all", label: `All (${snapshot.devices.length})` },
        { value: "unused", label: `Unused (${unusedCount})`, title: "Devices not placed on any sheet yet" },
      ]}
      onchange={setFilter}
    />
  </div>
  <input class="mb-2 w-full" placeholder="Search devices" aria-label="Search devices" bind:value={search} />
  {#if help.shown}<p class="mb-2 text-xs muted">Drag a device onto the canvas to add it — give it inputs and outputs in its panel.</p>{/if}
  <ul class="flex flex-col gap-0.5">
    {#each devices as d (posKey(d.pos))}
      {@const key = posKey(d.pos)}
      <li class="list-row group flex items-center gap-2 rounded-md pr-2" class:active={app.selectedDevice === key}>
        <button
          class="flex min-w-0 flex-1 items-center gap-2 py-1 pl-2 text-left"
          draggable={canEdit && d.state === "active"}
          ondragstart={(e) => drag(e, key)}
          onclick={() => openDevice(d)}
          oncontextmenu={(e) => deviceMenu(e, d)}
          title={`${d.defaultName}\n${d.block}`}
        >
          <DeviceIcon item={d.icon} />
          <span class="trim-text min-w-0 flex-1 truncate" class:muted={d.state !== "active"}>{deviceName(d)}</span>
          {#if d.state !== "active"}<span class="text-xs text-warn">{d.state}</span>
          {:else if d.outOfRange}<span class="text-xs text-warn" title={unreachable(snapshot.antenna).why}>{unreachable(snapshot.antenna).label}</span>{/if}
        </button>
        <IdentifyBadge device={d} />
      </li>
    {:else}
      <li class="text-sm muted">
        {filter === "unused" && snapshot.devices.length ? "Every device is used on a sheet." : "No devices. Place a connector next to a machine."}
      </li>
    {/each}
  </ul>
</section>
