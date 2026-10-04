<script lang="ts">
  import { onMount } from "svelte";
  import { SvelteFlowProvider } from "@xyflow/svelte";
  import { app, start, undo, redo } from "./lib/state.svelte";
  import TopBar from "./components/TopBar.svelte";
  import Sidebar from "./components/Sidebar.svelte";
  import Canvas from "./components/Canvas.svelte";
  import Inspector from "./components/Inspector.svelte";
  import ChartsPanel from "./components/inspector/ChartsPanel.svelte";
  import Toasts from "./components/Toasts.svelte";
  import Tooltips from "./components/Tooltips.svelte";
  import ContextMenu from "./components/ContextMenu.svelte";
  import Dialog from "./components/Dialog.svelte";
  import EmptyState from "./components/EmptyState.svelte";
  import { modals } from "./lib/dialog.svelte";

  onMount(start);

  // No browser menu on a right-click: the editor's own menus open where they make sense (the canvas, its cards,
  // wires and pins, the sidebar's rows), and elsewhere nothing does. Text boxes keep theirs, for cut, copy and paste.
  onMount(() => {
    const native = (e: MouseEvent) => {
      if (e.defaultPrevented) return;
      if (e.target instanceof Element && e.target.closest("input, textarea, [contenteditable]")) return;
      e.preventDefault();
    };
    document.addEventListener("contextmenu", native);
    return () => document.removeEventListener("contextmenu", native);
  });

  function typing(target: EventTarget | null): boolean {
    return target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement || target instanceof HTMLSelectElement;
  }

  function onkeydown(e: KeyboardEvent) {
    // Not behind an open dialog either: undo would change the page under the question it asks.
    if (!(e.ctrlKey || e.metaKey) || typing(e.target) || modals.open > 0) return;
    const key = e.key.toLowerCase();
    if (key === "z" && !e.shiftKey) {
      e.preventDefault();
      undo();
    } else if ((key === "z" && e.shiftKey) || key === "y") {
      e.preventDefault();
      redo();
    }
  }
</script>

<svelte:window {onkeydown} />

<div class="flex h-full flex-col">
  <TopBar />
  {#if !app.socketOpen}
    <EmptyState fill loading message="Connecting to the game…" />
  {:else if !app.inWorld}
    <EmptyState fill message="Join a world in Minecraft to edit its networks." />
  {:else if app.networks.length === 0}
    <EmptyState fill message="No networks you can see yet. Place a controller to start one." />
  {:else if !app.snapshot}
    <EmptyState fill loading message="Loading…" />
  {:else}
    <SvelteFlowProvider>
    <div class="flex min-h-0 flex-1">
      <Sidebar />
      <main class="relative min-w-0 flex-1">
        <Canvas />
        {#if app.panel === "charts"}<ChartsPanel />{/if}
      </main>
      <!-- Charts take the inspector's room as well. -->
      {#if app.panel !== "charts"}<Inspector />{/if}
    </div>
    </SvelteFlowProvider>
  {/if}
  <Toasts />
  <Dialog />
  <Tooltips />
  <ContextMenu />
</div>
