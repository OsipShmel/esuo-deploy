// T5.1 / T5 (scale design §8): memory of /network and the cost of its API on a big network. Not a test: a measurement,
// run by hand.
//   node e2e/measure-network.mjs            — system Chrome (the Playwright browser is not installed)
// Prints per stage: JS heap of the page, DOM nodes, and per API path the calls, bytes and slowest response.
import { chromium } from '@playwright/test';

const CENTER = process.env.E2E_CENTER_URL ?? 'http://localhost:8081';
const LOGIN = process.env.E2E_CENTER_LOGIN ?? 'network';
const PASSWORD = process.env.E2E_CENTER_PASSWORD ?? 'network123';

const browser = await chromium.launch({ channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const cdp = await page.context().newCDPSession(page);
await cdp.send('Performance.enable');
let api = new Map();
const started = new Map();
page.on('request', r => started.set(r, Date.now()));
page.on('response', async r => {
  const url = new URL(r.url());
  if (!url.pathname.startsWith('/api/')) return;
  const key = `${r.request().method()} ${url.pathname.replace(/\/[sb]\d[\w-]*/g, '/{id}')}`;
  let bytes = 0;
  try { bytes = (await r.body()).length; } catch { /* aborted */ }
  const a = api.get(key) ?? { calls: 0, bytes: 0, maxMs: 0, maxBytes: 0 };
  a.calls++; a.bytes += bytes; a.maxBytes = Math.max(a.maxBytes, bytes);
  a.maxMs = Math.max(a.maxMs, Date.now() - (started.get(r.request()) ?? Date.now()));
  api.set(key, a);
});

async function measure(stage) {
  const { usedSize, totalSize } = await cdp.send('Runtime.getHeapUsage');
  const { nodes } = await cdp.send('Memory.getDOMCounters');
  const mb = n => (n / 1048576).toFixed(1);
  console.log(`\n== ${stage}: JS heap ${mb(usedSize)} MB (allocated ${mb(totalSize)} MB), DOM nodes ${nodes}`);
  for (const [k, a] of [...api.entries()].sort((x, y) => y[1].bytes - x[1].bytes)) {
    console.log(`   ${k.padEnd(48)} ${String(a.calls).padStart(4)} calls  ${mb(a.bytes).padStart(8)} MB total  max ${mb(a.maxBytes)} MB  max ${a.maxMs} ms`);
  }
  api = new Map();
}

const t0 = Date.now();
await page.goto(`${CENTER}/network`);
await page.getByLabel('Логин').fill(LOGIN);
await page.getByLabel('Пароль').fill(PASSWORD);
await page.getByRole('button', { name: 'Войти' }).click();
await page.getByText(/На связи/).first().waitFor({ timeout: 120_000 });
console.log(`overview shown in ${Date.now() - t0} ms`);
await page.waitForTimeout(60_000);
await measure('Обзор, 60 с опроса');

const tiles = page.getByRole('link', { name: /^Регион / });
if (await tiles.count() > 0) {
  await tiles.first().click();
  await page.waitForTimeout(30_000);
  await measure('Регион, 30 с');
  await page.getByRole('region', { name: 'Отделения', exact: true }).getByRole('link', { name: /^Открыть / }).first().click();
  await page.waitForTimeout(20_000);
  await measure('Панель отделения, 20 с');
}

await page.getByRole('link', { name: 'Конфигурация' }).click();
await page.waitForTimeout(30_000);
await page.getByLabel('Поиск отделения').fill('s2-0001');
await page.waitForTimeout(15_000);
await measure('Конфигурация, 45 с, с поиском');

await page.getByRole('link', { name: 'Уведомления' }).click();
await page.waitForTimeout(20_000);
await measure('Уведомления, 20 с');

await page.goto(`${CENTER}/network`);
await page.waitForTimeout(30_000);
await measure('Снова обзор, 30 с');
await browser.close();
