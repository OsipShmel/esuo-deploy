import { defineConfig } from '@playwright/test';

/**
 * Смоук-проверки экранов по живому стенду (`docker compose up -d --build`), а не по dev-серверу.
 * Адреса: E2E_NODE_URL (по умолчанию http://localhost:8080), E2E_CENTER_URL (http://localhost:8081).
 */
export default defineConfig({
  testDir: 'e2e',
  timeout: 30_000,
  retries: 0,
  workers: 1,
  reporter: [['list']],
  use: { locale: 'ru-RU', timezoneId: 'Europe/Moscow', trace: 'retain-on-failure' },
  projects: [
    { name: 'desktop', use: { viewport: { width: 1280, height: 800 } } },
    { name: 'phone', use: { viewport: { width: 375, height: 812 }, isMobile: true, hasTouch: true } },
  ],
});
