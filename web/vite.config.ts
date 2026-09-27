import { defineConfig } from "vite";
import { svelte } from "@sveltejs/vite-plugin-svelte";
import tailwindcss from "@tailwindcss/vite";

// The game client serves the built files; there is no Vite dev server (the page must come from the game's own
// origin to pass its security checks). `npm run dev` rebuilds on change instead; refresh the browser to see it.
export default defineConfig(({ mode }) => ({
  plugins: [svelte(), tailwindcss()],
  base: "/",
  build: {
    outDir: "dist",
    emptyOutDir: true,
    sourcemap: mode === "development",
    minify: mode !== "development",
    chunkSizeWarningLimit: 2000,
  },
}));
