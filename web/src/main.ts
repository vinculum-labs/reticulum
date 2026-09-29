import { mount } from "svelte";
import "./app.css";
import App from "./App.svelte";
import { installEscape } from "./lib/escape";
import { request } from "./lib/state.svelte";

// Esc at the root closes the in-game editor (the game client hides it); F11 there toggles the game's fullscreen.
installEscape(
  () => request("closeEditor").catch(() => {}),
  () => request("toggleFullscreen").catch(() => {}),
);

mount(App, { target: document.getElementById("app")! });
