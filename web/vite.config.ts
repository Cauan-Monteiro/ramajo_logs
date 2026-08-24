import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// A API não tem CORS configurado: em dev o proxy põe front e API na mesma
// origem, por isso todo fetch usa caminho relativo (/api/...).
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/api": { target: "http://localhost:8080", changeOrigin: true },
    },
  },
});
