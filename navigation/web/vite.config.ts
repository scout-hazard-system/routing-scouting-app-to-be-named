import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Dev: `wrangler pages dev dist` serves the Functions gateway; plain `vite` proxies
// /api to it so the UI can be iterated with the real gateway rules in front.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 8793,
    strictPort: true,
    proxy: { "/api": { target: "http://127.0.0.1:8788", changeOrigin: true } }
  },
  build: { sourcemap: false, chunkSizeWarningLimit: 900 }
});
