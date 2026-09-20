import { useState, type FormEvent } from 'react';

/** «Сообщить о проблеме»: инцидент привязывается к окну и, если есть, к текущему талону. */
export function IncidentForm({ busy, onSubmit }: {
  busy: boolean; onSubmit: (v: { kind: 'technical' | 'operational'; description: string }) => Promise<boolean>;
}) {
  const [kind, setKind] = useState<'technical' | 'operational'>('technical');
  const [description, setDescription] = useState('');

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (await onSubmit({ kind, description: description.trim() })) setDescription('');
  }
  return (
    <form className="card form-grid" onSubmit={submit} aria-labelledby="incident-title">
      <h2 id="incident-title" style={{ margin: 0 }}>Сообщить о проблеме</h2>
      <label className="field">Тип
        <select value={kind} onChange={(e) => setKind(e.target.value as 'technical' | 'operational')}>
          <option value="technical">Техническая (оборудование, связь)</option>
          <option value="operational">Операционная (клиент, процесс)</option>
        </select>
      </label>
      <label className="field">Что случилось
        <textarea value={description} onChange={(e) => setDescription(e.target.value)} required maxLength={1000} rows={3}
          style={{ minHeight: 88, padding: 12, resize: 'vertical' }} />
      </label>
      <button type="submit" className="btn" disabled={busy || description.trim() === ''}>Отправить руководителю</button>
    </form>
  );
}
