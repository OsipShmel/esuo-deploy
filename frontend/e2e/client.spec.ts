import { expect, test, type Page } from '@playwright/test';
import { BRANCH, CENTER, NODE, tomorrow } from './env';

const TICKET_NUMBER = /^[А-ЯA-Z]-\d{3}$/;

async function noHorizontalScroll(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow, 'страница не должна прокручиваться вбок').toBeLessThanOrEqual(0);
}

test('главная центра: список отделений', async ({ page }) => {
  await page.goto(`${CENTER}/`);
  await expect(page.getByRole('heading', { name: 'Выберите отделение' })).toBeVisible();
  await expect(page.getByRole('link', { name: new RegExp(BRANCH) }).first()).toBeVisible();
  await noHorizontalScroll(page);
});

test('главная центра: отделение находится поиском', async ({ page }) => {
  await page.goto(`${CENTER}/`);
  await page.getByLabel('Поиск отделения').fill(BRANCH);
  await expect(page.getByRole('link', { name: new RegExp(BRANCH) }).first()).toBeVisible();
  await noHorizontalScroll(page);
});

test('страница отделения: услуги для записи', async ({ page }) => {
  await page.goto(`${CENTER}/b/${BRANCH}`);
  await expect(page.getByText('Записаться на время')).toBeVisible();
  await expect(page.getByText('слот 15 мин').first()).toBeVisible();
  await noHorizontalScroll(page);
});

test('запись на слот и отмена талона', async ({ page }) => {
  await page.goto(`${CENTER}/b/${BRANCH}`);
  await page.getByText('слот 15 мин').first().click();
  await page.locator('input[type="date"]').fill(tomorrow());
  await page.getByRole('button', { name: /^\d{2}:\d{2}$/ }).first().click();
  await page.getByRole('button', { name: /^Записаться на/ }).click();

  await expect(page.locator('.number')).toHaveText(TICKET_NUMBER);
  await expect(page.getByText('Записан на')).toBeVisible();
  await noHorizontalScroll(page);

  await page.getByRole('button', { name: 'Отменить талон' }).click();
  await page.getByRole('button', { name: 'Да, отменить' }).click();
  await expect(page.getByText('Талон отменён.')).toBeVisible();
});

test('запись на завтра: check-in сегодня недоступен и объясняет почему', async ({ page }) => {
  await page.goto(`${CENTER}/b/${BRANCH}`);
  await page.getByText('слот 15 мин').first().click();
  await page.locator('input[type="date"]').fill(tomorrow());
  await page.getByRole('button', { name: /^\d{2}:\d{2}$/ }).first().click();
  await page.getByRole('button', { name: /^Записаться на/ }).click();
  await expect(page.locator('.number')).toHaveText(TICKET_NUMBER);

  await page.getByRole('button', { name: 'Я в отделении' }).click();
  await page.getByPlaceholder(`demo:${BRANCH}`).fill(`demo:${BRANCH}`);
  await page.getByRole('button', { name: 'Подтвердить приход' }).click();
  await expect(page.getByRole('alert')).toHaveCount(1);          // одна ошибка, а не две

  await page.getByRole('button', { name: 'Закрыть' }).click();
  await page.getByRole('button', { name: 'Отменить талон' }).click();
  await page.getByRole('button', { name: 'Да, отменить' }).click();
  await expect(page.getByText('Талон отменён.')).toBeVisible();
});

test('талон по QR через узел (без центра): выдача, отложить, отмена', async ({ page }) => {
  await page.goto(`${NODE}/b/${BRANCH}/join?code=demo:${BRANCH}`);
  await page.getByRole('radio').first().check();
  await page.getByRole('button', { name: 'Получить талон' }).click();
  await expect(page.locator('.number')).toHaveText(TICKET_NUMBER);
  await expect(page.getByText('Ожидает')).toBeVisible();

  await page.getByRole('button', { name: 'Отложить' }).click();
  await page.getByRole('button', { name: '10 мин' }).click();
  await expect(page.getByText(/Вызов:/)).toBeVisible();          // с открытым окном «около N мин», с закрытым — «когда откроется окно»
  await expect(page.getByRole('button', { name: '10 мин' })).toBeHidden();   // ответ получен, панель закрылась

  await page.getByRole('button', { name: 'Отменить талон' }).click();
  await page.getByRole('button', { name: 'Да, отменить' }).click();
  await expect(page.getByText('Талон отменён.')).toBeVisible();
});
