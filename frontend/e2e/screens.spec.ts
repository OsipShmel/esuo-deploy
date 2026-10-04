import { expect, test, type Page } from '@playwright/test';
import { BRANCH, CENTER, CENTER_LOGIN, CENTER_PASSWORD, NODE } from './env';

test('табло: включение звука, QR и счётчик ожидающих', async ({ page }) => {
  await page.goto(`${NODE}/board`);
  await page.getByText('Включить звук').click();
  await expect(page.getByText('ВЫЗВАНЫ', { exact: false })).toBeVisible();
  await expect(page.getByRole('img', { name: /QR-код/ })).toBeVisible();
  await expect(page.getByText(/Ждут:/)).toBeVisible();
});

test('сеть отделений: без входа — форма входа, после входа — карточки и вкладки', async ({ page }) => {
  await page.goto(`${CENTER}/network`);
  await expect(page.getByLabel('Логин')).toBeVisible();
  await page.getByLabel('Логин').fill(CENTER_LOGIN);
  await page.getByLabel('Пароль').fill(CENTER_PASSWORD);
  await page.getByRole('button', { name: 'Войти' }).click();
  await expect(page.getByText(/На связи \d+ из \d+/)).toBeVisible();
  // Перезагрузка не выкидывает на вход: первые запросы уже идут с токеном из сохранённой сессии.
  await page.reload();
  await expect(page.getByText(/На связи \d+ из \d+/)).toBeVisible();
  await expect(page.getByText(new RegExp(`^${BRANCH} ·`)).first()).toBeVisible();
  await page.getByRole('link', { name: 'Конфигурация' }).click();
  await expect(page.getByRole('heading', { name: 'Публикация и применение' })).toBeVisible();
  await page.getByRole('link', { name: 'Уведомления' }).click();
  await expect(page.locator('.tab.active', { hasText: 'Уведомления' })).toBeVisible();
});

test('сеть отделений: строка «Узел» — снимок скачивается, логи дают ссылку, графики ведут на дашборд', async ({ page }) => {
  await page.goto(`${CENTER}/network`);
  await page.getByLabel('Логин').fill(CENTER_LOGIN);
  await page.getByLabel('Пароль').fill(CENTER_PASSWORD);
  await page.getByRole('button', { name: 'Войти' }).click();
  const node = page.getByRole('region', { name: `Узел ${BRANCH}` });
  await expect(node.getByText(/CPU \d+%/)).toBeVisible();
  await expect(node.getByRole('link', { name: 'Графики' })).toHaveAttribute('href', new RegExp(`/d/esuo-branch/\\?var-branch=${BRANCH}$`));

  const download = page.waitForEvent('download');
  await node.getByRole('button', { name: 'Снять диагностику' }).click();
  expect((await download).suggestedFilename()).toMatch(new RegExp(`^snapshot-${BRANCH}-.*\\.json$`));

  await node.getByRole('button', { name: /Подробные логи/ }).click();
  await expect(node.getByRole('link', { name: /Открыть логи \(\d+ строк\)/ })).toBeVisible({ timeout: 20_000 });
});

async function loginNetwork(page: Page) {
  await page.goto(`${CENTER}/network`);
  await page.getByLabel('Логин').fill(CENTER_LOGIN);
  await page.getByLabel('Пароль').fill(CENTER_PASSWORD);
  await page.getByRole('button', { name: 'Войти' }).click();
  await expect(page.getByText(/На связи \d+ из \d+/)).toBeVisible();
}

test('сеть: строка «Центр» и панель отделения', async ({ page }) => {
  await loginNetwork(page);
  await expect(page.getByRole('region', { name: 'Центр' }).getByText(/куча [\d,]+ из [\d,]+ ГБ/)).toBeVisible();
  await page.getByRole('link', { name: `Открыть ${BRANCH}` }).first().click();
  await expect(page).toHaveURL(new RegExp(`/network/b/${BRANCH}$`));
  await expect(page.getByRole('region', { name: `Узел ${BRANCH}` })).toBeVisible();
});

test('сеть: регион — сводка, поиск и страницы', async ({ page }) => {
  await loginNetwork(page);
  const tiles = page.getByRole('link', { name: /^Регион / });
  test.skip(await tiles.count() === 0, 'регионов нет: рой на стенде не запускался');
  await tiles.first().click();
  await expect(page).toHaveURL(/\/network\/regions\//);
  await expect(page.getByText(/^[\d\s\u00a0]+–[\d\s\u00a0]+ из [\d\s\u00a0]+$/)).toBeVisible();   // «1–24 из 62 500»
  const list = page.getByRole('region', { name: 'Отделения', exact: true });
  const id = (await list.getByRole('link', { name: /^Открыть / }).first().getAttribute('aria-label'))!.replace('Открыть ', '');
  await list.getByLabel('Поиск отделения').fill(id);
  await expect(list.getByRole('link', { name: `Открыть ${id}` })).toBeVisible();
  await list.getByRole('link', { name: `Открыть ${id}` }).click();
  await expect(page).toHaveURL(new RegExp(`/network/b/${id}$`));
});

test('конфигурация: отделение находится поиском, список — страницами', async ({ page }) => {
  await loginNetwork(page);
  await page.getByRole('link', { name: 'Конфигурация' }).click();
  await page.getByLabel('Поиск отделения').fill(BRANCH);
  await page.getByRole('button', { name: `Настроить ${BRANCH}` }).click();
  await expect(page.getByLabel('Область')).toHaveValue(BRANCH);
});

for (const [path, title] of [['/admin', 'ЕСУО · Руководитель отделения'], ['/operator', 'ЕСУО · Оператор'], ['/demo', 'ЕСУО · Демо-пульт']] as const) {
  test(`${path}: без входа показывается форма входа`, async ({ page }) => {
    await page.goto(`${NODE}${path}`);
    await expect(page.getByRole('heading', { name: new RegExp(title.replace('ЕСУО · ', '')) })).toBeVisible();
    await expect(page.getByLabel('Логин')).toBeVisible();
    await expect(page.getByLabel('Пароль')).toBeVisible();
  });
}

/** Экраны за входом: пароль берётся из окружения, без него проверки пропускаются. */
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD;
test.describe('admin под руководителем', () => {
  test.skip(!ADMIN_PASSWORD, 'задайте E2E_ADMIN_PASSWORD');

  test('вкладки открываются, «Нагрузка» не падает без ручек B', async ({ page }) => {
    await page.goto(`${NODE}/admin`);
    await page.getByLabel('Логин').fill(process.env.E2E_ADMIN_LOGIN ?? 'admin');
    await page.getByLabel('Пароль').fill(ADMIN_PASSWORD!);
    await page.getByRole('button', { name: 'Войти' }).click();
    await expect(page.getByRole('tab', { name: 'Очередь' })).toBeVisible();
    for (const name of ['Нагрузка', 'Аналитика', 'Инциденты', 'Настройки', 'Справочники']) {
      await page.getByRole('tab', { name }).click();
      await expect(page.getByRole('tab', { name, selected: true })).toBeVisible();
      await expect(page.getByText('Что-то пошло не так')).toHaveCount(0);
    }
  });
});
