import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The dev server proxies /api to Django, so the browser sees one origin (no CORS setup needed).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { "/api": { target: process.env.CAREERPILOT_API || "http://127.0.0.1:8000", changeOrigin: true } },
  },
  test: { environment: "node", include: ["tests/**/*.test.js"] },
});
