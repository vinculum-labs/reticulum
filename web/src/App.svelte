<script lang="ts">
  import { onMount } from "svelte";
  import { app, start, undo, redo } from "./lib/state.svelte";
  import TopBar from "./components/TopBar.svelte";
  import Sidebar from "./components/Sidebar.svelte";
  import Canvas from "./components/Canvas.svelte";
  import Inspector from "./components/Inspector.svelte";
  import Toasts from "./components/Toasts.svelte";

  onMount(start);

  function typing(target: EventTarget | null): boolean {
    return target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement || target instanceof HTMLSelectElement;
  }

  function onkeydown(e: KeyboardEvent) {
    if (!(e.ctrlKey || e.metaKey) || typing(e.target)) return;
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
    <div class="grid flex-1 place-items-center muted">Connecting to the game…</div>
  {:else if !app.inWorld}
    <div class="grid flex-1 place-items-center muted">Join a world in Minecraft to edit its networks.</div>
  {:else if app.networks.length === 0}
    <div class="grid flex-1 place-items-center muted">No networks you can see yet. Place a controller to start one.</div>
  {:else if !app.snapshot}
    <div class="grid flex-1 place-items-center muted">Loading…</div>
  {:else}
    <div class="flex min-h-0 flex-1">
      <Sidebar />
      <main class="relative min-w-0 flex-1">
        <Canvas />
      </main>
      <Inspector />
    </div>
  {/if}
  <Toasts />
</div>
