const TITLES: Record<string, string> = { default: 'Обычный клиент', pensioner: 'Пенсионер', special: 'Спец-категория' };

/** Категория клиента влияет на приоритет (дедлайн). Проверка права на категорию вне рамок MVP (дизайн-док 22.1). */
export function CategoryPicker({ categories, value, onChange }: { categories: string[]; value: string; onChange: (c: string) => void }) {
  const list = categories.length ? categories : ['default'];
  if (list.length <= 1) return null;
  return (
    <fieldset style={{ border: 0, padding: 0, margin: 0, display: 'grid', gap: 8 }}>
      <legend className="muted small" style={{ marginBottom: 8 }}>Категория</legend>
      {list.map((c) => (
        <label key={c} className="choice">
          <input type="radio" name="category" value={c} checked={value === c} onChange={() => onChange(c)} />
          {TITLES[c] ?? c}
        </label>
      ))}
    </fieldset>
  );
}
