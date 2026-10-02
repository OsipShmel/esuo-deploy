import { expect, test } from '@playwright/test';
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
