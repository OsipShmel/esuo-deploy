import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import react from '@vitejs/plugin-react';
import { defineConfig, type Plugin } from 'vite';

// Многостраничная сборка (дизайн-док/концепция, раздел 6): по странице на приложение.
const pages = ['client', 'board', 'operator', 'admin', 'network', 'demo'].filter((p) => existsSync(resolve(__dirname, `${p}.html`)));

const node = process.env.VITE_NODE_URL ?? 'http://localhost:8080';
const center = process.env.VITE_CENTER_URL ?? 'http://localhost:8081';

/**
 * В dev те же правила, что у SpaRoutes узла и центра: `/` и `/b/**` — client, остальное — по имени страницы.
 * Так клиентские маршруты (`/b/b01/t/…`) открываются на dev-сервере без перезагрузки со страницы 404.
 */
function spaFallback(): Plugin {
  return {
    name: 'esuo-spa-fallback',
    configureServer(server) {
      server.middlewares.use((req, _res, next) => {
        const url = req.url ?? '';
        const path = url.split('?')[0] ?? '';
        const query = url.includes('?') ? `?${url.split('?')[1]}` : '';
        const page = path === '/' || path.startsWith('/b/') ? 'client'
          : (['board', 'operator', 'admin', 'demo', 'network'] as const).find((p) => path === `/${p}` || path.startsWith(`/${p}/`));
        if (page && !path.includes('.')) req.url = `/${page}.html${query}`;
        next();
      });
    },
  };
}

export default defineConfig({
  plugins: [react(), spaFallback()],
  resolve: { alias: { '@shared': resolve(__dirname, 'src/shared') } },
  build: {
    rollupOptions: { input: Object.fromEntries(pages.map((p) => [p, resolve(__dirname, `${p}.html`)])) },
  },
  server: {
    // По умолчанию /api → узел (:8080). VITE_TARGET=center переключает на центр (:8081): так client и network
    // проверяются в режиме центра (frontend-concept, раздел 6).
    proxy: {
      '/api': { target: process.env.VITE_TARGET === 'center' ? center : node, changeOrigin: true },
    },
  },
});
