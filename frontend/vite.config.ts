import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// Многостраничная сборка (дизайн-док/концепция, раздел 6): по странице на приложение.
const pages = ['client', 'board', 'operator', 'admin', 'network', 'demo'].filter((p) => existsSync(resolve(__dirname, `${p}.html`)));

const node = process.env.VITE_NODE_URL ?? 'http://localhost:8080';
const center = process.env.VITE_CENTER_URL ?? 'http://localhost:8081';

export default defineConfig({
  plugins: [react()],
  resolve: { alias: { '@shared': resolve(__dirname, 'src/shared') } },
  build: {
    rollupOptions: { input: Object.fromEntries(pages.map((p) => [p, resolve(__dirname, `${p}.html`)])) },
  },
  server: {
    // client и network работают против центра (:8081), остальные — против узла (:8080).
    proxy: {
      '/api': { target: node, changeOrigin: true },
      '/center-api': { target: center, changeOrigin: true, rewrite: (p) => p.replace(/^\/center-api/, '/api') },
    },
  },
});
