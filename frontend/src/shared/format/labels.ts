/** Русские подписи статусов, источников, категорий (концепция 3.2). */
export const SOURCE_LABEL: Record<string, string> = { appointment: 'Запись', qr: 'QR', walkin: 'Живая очередь' };
export const STATUS_LABEL: Record<string, string> = {
  booked: 'Записан',
  waiting: 'Ожидает',
  called: 'Вызван',
  serving: 'Обслуживается',
  done: 'Обслужен',
  cancelled: 'Отменён',
  no_show: 'Не подошёл',
};
export const WINDOW_STATUS_LABEL: Record<string, string> = { open: 'Открыто', closing: 'Закрывается', closed: 'Закрыто' };
export const CATEGORY_LABEL: Record<string, string> = { default: 'Обычный', pensioner: 'Пенсионер', svo: 'СВО' };

export const label = (map: Record<string, string>, key: string): string => map[key] ?? key;
