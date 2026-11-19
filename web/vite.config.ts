import { fileURLToPath } from "node:url"
import tailwindcss from "@tailwindcss/vite"
import react from "@vitejs/plugin-react"
import { defineConfig } from "vite"

export default defineConfig(({ mode }) => ({
  base: mode === "demo" ? "./" : "/",
  plugins: [react(), tailwindcss()],
  resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
  build: mode === "demo" ? { assetsInlineLimit: () => true, rollupOptions: { output: { inlineDynamicImports: true } } } : {},
  server: {
    fs: { allow: [fileURLToPath(new URL(".", import.meta.url)), fileURLToPath(new URL("../scripts/oci-cloud-shell-setup.sh", import.meta.url))] },
    proxy: { "/api": { target: "http://127.0.0.1:9090", changeOrigin: false } },
  },
}))
