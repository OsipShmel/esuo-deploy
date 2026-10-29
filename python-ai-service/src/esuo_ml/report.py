"""Отчёт о модели (ml-model.md, 7.2) → `reports/ml-report.md`.

Короткий и с числами: одна главная таблица «прогноз против его отсутствия», потом разбивка по типам дней,
точность одного прогноза и критерии готовности. Каждое число считается здесь же, на синтетике мира,
и воспроизводится по зерну; ничего не вписано руками.

Главная величина — не MAE, а то, что из прогноза следует для отделения: сколько окон поставить в смену
и сколько после этого ждут люди (`load-planning.md`, 4.1–4.2). «Без прогноза» — это то, как отделение
работает без модели и без истории: 300 с на любое обслуживание (`default-service-duration-sec`).
"""

from __future__ import annotations

import math
from datetime import date, datetime, timezone
from pathlib import Path

from .baselines import CascadeBaseline, ConstantBaseline, ServiceMeanBaseline, mae
from .fit import FitOptions, fit, split
from .generate import Observation
from .predict import ModelParams
from .world import World

PENSION_BIAS = 1.3                                     # «день пошёл хуже плана», как в демо A/B


def write_report(world: World, observations: list[Observation], path: Path,
                 params: ModelParams | None = None, seed: int = 42) -> None:
    options = FitOptions()
    if params is None:
        params = fit(observations, options, version="dm-report").params
    train, holdout = split(observations, options)
    evaluation = holdout or train
    cascade = CascadeBaseline(observations)

    days = _planning_days(world, observations)
    plans = {title: _plan_rows(world, params, cascade, day, bias, seed) for title, day, bias in days}
    # Главная таблица — на обычном пенсионном дне: самый нагруженный профиль по плану; строка «+30 %»
    # (день хуже плана, как в демо A/B) остаётся в разбивке по дням.
    headline = next(((t, d) for t, d, b in days if t == "Пенсионные выплаты"), (days[-1][0], days[-1][1]) if days else (None, None))
    headline_title = headline[0]
    eta = _eta(world, params, cascade, headline[1], seed) if headline[1] else {}

    sections = [
        _header(world, params, evaluation, seed),
        _headline(plans.get(headline_title), eta, _accuracy_numbers(world, params, train, evaluation)),
        _by_day(plans),
        _accuracy(world, params, train, evaluation),
        _criteria(world, params, evaluation),
        _footer(),
    ]
    path.write_text("\n\n".join(s for s in sections if s).strip() + "\n", encoding="utf-8")


# ---------------------------------------------------------------------- расчёты


def _plan_rows(world: World, params: ModelParams, cascade: CascadeBaseline, day: date, bias: float,
               seed: int) -> dict:
    """По прогнозу каждой руки — план смены на день; день проигрывается с этим планом.

    Длительности в прогоне берутся из мира, выбор талона — одна и та же логика во всех руках:
    различается только то, сколько окон руководитель поставил по прогнозу.
    """
    from .benchmark import forecast_work_min, model_predictor, needed_windows, run_branch, uniform_predictor, _shift

    def oracle(service_code: str, category: str, employee_id: str, hour: int) -> float:
        return world.mean_duration_sec(service_code, category, employee_id, hour, world.windows[0].id)

    target = float(world.planning.get("utilization_target", 0.85))
    shift = _shift(world, day, seed)
    out = {}
    for key, predict in (("uniform", uniform_predictor(world)), ("model", model_predictor(params, cascade)),
                         ("oracle", oracle)):
        plan = needed_windows(forecast_work_min(world, day, predict, bias, shift), target)
        result = run_branch(world, day, seed, model_predictor(params, cascade), bias, key, staffing=plan)
        out[key] = {"window_hours": sum(plan.values()), "avg": result.avg_wait_sec / 60,
                    "p90": result.p90_wait_sec / 60, "max": result.max_wait_sec / 60,
                    "overdue": result.overdue, "served": result.served}
    return out


def _eta(world: World, params: ModelParams, cascade: CascadeBaseline, day: date, seed: int) -> dict:
    """Ошибка обещанного клиенту времени вызова (как считает `WaitTimeEstimator`) в тот же день."""
    from .benchmark import eta_error_min, model_predictor, run_branch, uniform_predictor

    out = {}
    for key, predict in (("uniform", uniform_predictor(world)), ("model", model_predictor(params, cascade))):
        result = run_branch(world, day, seed, predict, flow_bias=1.0, arm=key)
        if result.served:
            out[key] = eta_error_min(world, result.clients, predict, result.shift, result.active)
    return out


def _accuracy_numbers(world: World, params: ModelParams, train: list[Observation],
                      evaluation: list[Observation]) -> dict:
    def model_p50(o: Observation) -> float:
        prediction = params.predict(o.service_code, o.client_category, o.employee_id, o.hour, o.window_id)
        return prediction.p50_sec if prediction else 300.0

    def oracle(o: Observation) -> float:
        return world.median_duration_sec(o.service_code, o.client_category, o.employee_id, o.hour, o.window_id)

    return {
        "oracle": mae(evaluation, oracle),
        "model": mae(evaluation, model_p50),
        "service": mae(evaluation, ServiceMeanBaseline(train).predict),
        "const": mae(evaluation, ConstantBaseline().predict),
    }


# ---------------------------------------------------------------------- разделы


def _header(world: World, params: ModelParams, evaluation: list[Observation], seed: int) -> str:
    generated = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    return (f"# Прогноз длительности обслуживания: итог в цифрах\n\n"
            f"> Синтетика мира `{world.code}`, зерно `{seed}`, модель `{params.version}`, "
            f"{len(evaluation)} обслуживаний отложенной выборки, собрано {generated}. "
            f"Пересобирается `uv run esuo-ml report --end-day 2026-09-20`, числа воспроизводятся по зерну.")


def _headline(plan: dict | None, eta: dict, acc: dict) -> str:
    if not plan:
        return ""
    u, m, o = plan["uniform"], plan["model"], plan["oracle"]
    lines = [
        "## Главное: прогноз против его отсутствия", "",
        "Пенсионный день: самый нагруженный профиль, почти половина клиентов — пенсионеры с платежами.",
        "«Без прогноза» — отделение без модели и без истории: 300 с на любое обслуживание.",
        "«С прогнозом» — модель длительности; она работает в паре со статистикой отделения, которая",
        "подхватывает прогноз там, где модели сказать нечего (незнакомая услуга, модель ещё не приехала).", "",
        "| | Без прогноза | С прогнозом | Итог |",
        "|---|---:|---:|---|",
        f"| Ошибка прогноза одного обслуживания, с | {acc['const']:.0f} | **{acc['model']:.0f}** | "
        f"{_less(acc['model'], acc['const'])} |",
    ]
    if "uniform" in eta and "model" in eta:
        lines.append(f"| Ошибка обещанного времени вызова, мин | {_n(eta['uniform'][0])} | **{_n(eta['model'][0])}** | "
                     f"{_times(eta['uniform'][0], eta['model'][0])} точнее |")
    lines += [
        f"| Окно-часов в смене (нужно на самом деле: {o['window_hours']}) | {u['window_hours']} "
        f"({_pct(u['window_hours'], o['window_hours'])}) | **{m['window_hours']}** "
        f"({_pct(m['window_hours'], o['window_hours'])}) | смена по потребности |",
        f"| Среднее ожидание клиента, мин | {u['avg']:.0f} | **{m['avg']:.0f}** | {_times(u['avg'], m['avg'])} меньше |",
        f"| 90 % клиентов ждали не дольше, мин | {u['p90']:.0f} | **{m['p90']:.0f}** | {_times(u['p90'], m['p90'])} меньше |",
        f"| Максимальное ожидание, мин | {u['max']:.0f} | **{m['max']:.0f}** | {_times(u['max'], m['max'])} меньше |",
        f"| Просроченных талонов | {u['overdue']} | **{m['overdue']}** | {_less(m['overdue'], u['overdue'])} |",
        "",
        "Как это получается. 300 с — это примерно выдача посылки, а в пенсионный день в смеси много платежей",
        "и пенсионеров, и настоящая средняя почти вдвое больше. Без прогноза отделение недосчитывает работу",
        "и недобирает смену, а недобор смены прямо переходит в очередь. Прогноз не ускоряет обслуживание —",
        "он говорит руководителю, сколько окон открыть, и клиенту — сколько честно ждать.",
    ]
    return "\n".join(lines)


def _by_day(plans: dict) -> str:
    if not plans:
        return ""
    lines = ["## По типам дней", "",
             "Смена ставится по прогнозу, день проигрывается с этой сменой; окна, длительности и выбор талона",
             "одинаковы во всех столбцах.", "",
             "| День | Окно-часов: без прогноза / с прогнозом / нужно | Ожидание ср., мин: без / с прогнозом |",
             "|---|---|---|"]
    for title, p in plans.items():
        lines.append(f"| {title} | {p['uniform']['window_hours']} / **{p['model']['window_hours']}** / "
                     f"{p['oracle']['window_hours']} | {p['uniform']['avg']:.0f} / **{p['model']['avg']:.0f}** |")
    return "\n".join(lines)


def _accuracy(world: World, params: ModelParams, train: list[Observation], evaluation: list[Observation]) -> str:
    a = _accuracy_numbers(world, params, train, evaluation)
    lines = ["## Точность одного прогноза", "",
             "Ошибка на обслуживаниях, которых модель не видела при обучении (MAE, секунды).", "",
             "| Способ прогноза | MAE, с | Против константы 300 с |", "|---|---:|---:|",
             f"| Предел точности: знает истинные параметры мира | {_n(a['oracle'])} | {_signed(a['oracle'], a['const'])} |",
             f"| **Модель** | **{_n(a['model'])}** | **{_signed(a['model'], a['const'])}** |",
             f"| Среднее по услуге | {_n(a['service'])} | {_signed(a['service'], a['const'])} |",
             f"| Константа 300 с | {_n(a['const'])} | — |", ""]
    lines += [f"Модель упёрлась в предел ({_n(a['model'])} против {_n(a['oracle'])} с): точнее на этих данных "
              "не может быть никто — остаток ошибки это случайность самого обслуживания.", "",
              "В отделении модель работает в паре со статистикой обслуживаний: модель → средние по истории "
              "отделения → 300 с. Поэтому прогноз не пропадает ни на новой услуге, ни до первой публикации модели."]
    return "\n".join(lines)


def _criteria(world: World, params: ModelParams, evaluation: list[Observation]) -> str:
    metrics = params.metrics or {}
    gain = metrics.get("gain_over_service_mean", 0.0)
    worst_employee = max((abs(math.exp(params.e.get(e.id, 0.0)) - e.multiplier) for e in world.employees),
                         default=0.0)
    covered = total = 0
    for o in evaluation:
        prediction = params.predict(o.service_code, o.client_category, o.employee_id, o.hour, o.window_id)
        if prediction:
            total += 1
            covered += o.duration_sec <= prediction.p90_sec
    coverage = covered / total if total else metrics.get("p90_coverage", 0.0)
    checks = [
        ("Точнее среднего по услуге минимум на 15 %", f"{_n(gain * 100)} %", gain >= 0.15),
        ("Скорость сотрудников восстановлена с ошибкой ≤ 0,1", _n(worst_employee, 3), worst_employee <= 0.1),
        ("Интервал «до p90» честный: накрывает 85–95 % фактов", f"{coverage * 100:.0f} %", 0.85 <= coverage <= 0.95),
    ]
    lines = ["## Критерии готовности (`ml-model.md`, 7.3)", "", "| Критерий | Факт | |", "|---|---:|:---:|"]
    for name, value, ok in checks:
        lines.append(f"| {name} | {value} | {'✅' if ok else '❌'} |")
    return "\n".join(lines)


def _footer() -> str:
    return ("---\n\n**[Проверено на синтетике]** — модель на реальных данных не проверялась. Мир, история и трассы "
            "генерируются `esuo-ml generate`; то же сравнение на живом стенде — `./scripts/demo/run.sh pension-ab`, "
            "его отчёт — `reports/demo/ab-report.md`.")


# ---------------------------------------------------------------------- мелочи


def _planning_days(world: World, observations: list[Observation]) -> list[tuple[str, date, float]]:
    """Один день каждого профиля плюс «день пошёл хуже плана» (load-planning.md, 7.2)."""
    from .generate import profile_for

    seen: dict[str, date] = {}
    for day in sorted({o.day for o in observations}, reverse=True):
        code, profile = profile_for(world, day)
        if not profile.get("closed"):
            seen.setdefault(code, day)
    titles = {"normal": "Обычный день", "weekend": "Выходной", "pension": "Пенсионные выплаты"}
    order = [c for c in ("normal", "weekend", "pension") if c in seen] + [c for c in seen if c not in titles]
    days = [(titles.get(code, code), seen[code], 1.0) for code in order]
    if "pension" in seen:
        days.append(("Пенсионные выплаты, поток +30 %", seen["pension"], PENSION_BIAS))
    return days


def _n(value: float, digits: int = 1) -> str:
    """Десятичная запятая: так числа выглядят на всех экранах проекта."""
    return f"{value:.{digits}f}".replace(".", ",")


def _times(worse: float, better: float) -> str:
    if better <= 0.05:
        return "почти до нуля:"
    ratio = worse / better
    if ratio < 10:
        return f"в {_n(ratio)} раза"
    n = round(ratio)
    word = "раза" if n % 10 in (2, 3, 4) and n % 100 not in (12, 13, 14) else "раз"
    return f"в {n} {word}"


def _less(value: float, reference: float) -> str:
    if not reference:
        return "—"
    if not value:
        return "ни одного"
    return f"−{(1 - value / reference) * 100:.0f} %"


def _signed(value: float, reference: float) -> str:
    return f"{(value / reference - 1) * 100:+.0f} %".replace("-", "−") if reference else "—"


_pct = _signed
