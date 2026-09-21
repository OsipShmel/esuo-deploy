import { Component, type ReactNode } from 'react';
import './components.css';

/** Последний рубеж: если экран упал, вместо пустой страницы — сообщение с текстом ошибки и кнопка «Обновить». */
export class ErrorBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
  state: { error: Error | null } = { error: null };

  static getDerivedStateFromError(error: Error) {
    return { error };
  }

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;
    return (
      <div className="page" style={{ maxWidth: 640, margin: '48px auto', padding: 16 }}>
        <div className="banner tone-danger" role="alert">
          <div className="banner-body">
            <strong>Что-то пошло не так</strong>
            <div>Экран остановился из-за ошибки. Обновите страницу; если повторится, сообщите текст ниже.</div>
            <small>{error.message}</small>
          </div>
        </div>
        <p><button type="button" className="btn btn-primary" onClick={() => window.location.reload()}>Обновить страницу</button></p>
      </div>
    );
  }
}
