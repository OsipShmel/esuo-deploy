import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@shared/api/http';
import { EmptyState, Loading, ProblemAlert } from '@shared/ui';
import { createUser, getUsers, updateUser, type UserView } from '../../api';
import { useToast } from '../../toast';

const ROLE: Record<string, string> = { OPERATOR: 'Оператор', BRANCH_ADMIN: 'Руководитель' };

/** Пользователи отделения. Пароль вводится один раз и нигде не показывается; конфликт логина — понятная фраза. */
export function UsersPanel({ branch }: { branch: string }) {
  const qc = useQueryClient();
  const toast = useToast();
  const q = useQuery({ queryKey: ['users', branch], queryFn: () => getUsers(branch) });
  const [form, setForm] = useState({ login: '', full_name: '', role: 'OPERATOR', password: '' });
  const [editing, setEditing] = useState<string | null>(null);
  const [newPassword, setNewPassword] = useState('');

  const done = (text: string) => { toast(text); void qc.invalidateQueries({ queryKey: ['users', branch] }); void qc.invalidateQueries({ queryKey: ['config'] }); void qc.invalidateQueries({ queryKey: ['versions'] }); };
  const create = useMutation({
    mutationFn: () => createUser(branch, form),
    onSuccess: (r) => { done(`Пользователь создан, опубликована версия v${r.config_version}`); setForm({ login: '', full_name: '', role: 'OPERATOR', password: '' }); },
  });
  const change = useMutation({
    mutationFn: (v: { id: string; body: { password?: string; role?: string; active?: boolean } }) => updateUser(branch, v.id, v.body),
    onSuccess: (r) => { done(`Сохранено, опубликована версия v${r.config_version}`); setEditing(null); setNewPassword(''); },
  });

  const loginTaken = create.error instanceof ApiError && create.error.code === 'LOGIN_TAKEN';
  const submit = (e: FormEvent) => { e.preventDefault(); create.mutate(); };

  return (
    <section className="card stack" aria-label="Пользователи отделения">
      <h2>Пользователи отделения {branch}</h2>
      {q.isLoading ? <Loading /> : !q.data ? <ProblemAlert error={q.error} /> : q.data.length === 0 ? <EmptyState title="Пользователей нет" /> : (
        <div className="table-wrap"><table>
          <thead><tr><th>Логин</th><th>ФИО</th><th>Роль</th><th>Статус</th><th /></tr></thead>
          <tbody>{q.data.map((u) => <UserRow key={u.id} u={u} busy={change.isPending} editing={editing === u.id} newPassword={newPassword}
            onEdit={() => { setEditing(editing === u.id ? null : u.id); setNewPassword(''); }} onPassword={setNewPassword}
            onSave={(body) => change.mutate({ id: u.id, body })} />)}</tbody>
        </table></div>
      )}
      <ProblemAlert error={change.error} />

      <form className="stack" onSubmit={submit}>
        <h3>Новый пользователь</h3>
        <div className="form-grid">
          <label className="field">Логин<input value={form.login} onChange={(e) => setForm({ ...form, login: e.target.value })} required minLength={3} autoComplete="off" /></label>
          <label className="field">ФИО<input value={form.full_name} onChange={(e) => setForm({ ...form, full_name: e.target.value })} required /></label>
          <label className="field">Роль
            <select value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value })}><option value="OPERATOR">Оператор</option><option value="BRANCH_ADMIN">Руководитель</option></select></label>
          <label className="field">Пароль (показывается один раз — при вводе)
            <input type="password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} required autoComplete="new-password" /></label>
        </div>
        {loginTaken ? <div className="banner tone-danger" role="alert">Такой логин уже есть в отделении.</div> : <ProblemAlert error={create.error} />}
        {create.error instanceof ApiError && create.error.errors.length > 0 && (
          <ul className="field-error" style={{ margin: 0, paddingLeft: 20 }}>{create.error.errors.map((e, i) => <li key={i}>{e.field ? `${e.field}: ` : ''}{e.message}</li>)}</ul>
        )}
        <div><button type="submit" className="btn btn-primary" disabled={create.isPending}>Добавить</button></div>
      </form>
    </section>
  );
}

function UserRow({ u, busy, editing, newPassword, onEdit, onPassword, onSave }: {
  u: UserView; busy: boolean; editing: boolean; newPassword: string; onEdit: () => void; onPassword: (p: string) => void;
  onSave: (b: { password?: string; role?: string; active?: boolean }) => void;
}) {
  return (
    <>
      <tr style={u.active ? undefined : { opacity: 0.6 }}>
        <td>{u.login}</td><td>{u.full_name ?? '—'}</td>
        <td>
          <select value={u.role} disabled={busy} aria-label={`Роль ${u.login}`} onChange={(e) => onSave({ role: e.target.value })}>
            {Object.entries(ROLE).map(([k, l]) => <option key={k} value={k}>{l}</option>)}
          </select>
        </td>
        <td>{u.active ? 'Работает' : 'Отключён'}</td>
        <td className="row">
          <button type="button" className="btn btn-sm" onClick={onEdit}>Сменить пароль</button>
          <button type="button" className={`btn btn-sm${u.active ? ' btn-danger' : ''}`} disabled={busy} onClick={() => onSave({ active: !u.active })}>{u.active ? 'Отключить' : 'Включить'}</button>
        </td>
      </tr>
      {editing && (
        <tr><td colSpan={5}>
          <div className="row">
            <input type="password" value={newPassword} onChange={(e) => onPassword(e.target.value)} placeholder="Новый пароль" autoComplete="new-password" aria-label="Новый пароль" />
            <button type="button" className="btn btn-primary btn-sm" disabled={busy || newPassword === ''} onClick={() => onSave({ password: newPassword })}>Сохранить пароль</button>
          </div>
        </td></tr>
      )}
    </>
  );
}
