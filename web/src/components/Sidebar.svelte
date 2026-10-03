<script lang="ts">
  import DeviceList from "./sidebar/DeviceList.svelte";
  import FilterTemplates from "./sidebar/FilterTemplates.svelte";
  import SheetTree from "./sidebar/SheetTree.svelte";

  /** The left column: the sheet tree and blueprints, the devices, the filter templates. */
</script>

<aside class="flex w-72 shrink-0 flex-col overflow-y-auto border-r border-line bg-panel">
  <SheetTree />
  <DeviceList />
  <FilterTemplates />
</aside>
