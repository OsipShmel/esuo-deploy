"""Командная строка: `generate`, `fit`, `report`, `predict`.

Всё детерминировано: одинаковый `--seed` и один мир дают одинаковые файлы. Коды выхода важны
для скриптов: `fit` возвращает 1, если версия не прошла приёмку (ml-model.md 4.3), чтобы негодная
модель не ушла в центр по невнимательности.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import date, timedelta
from pathlib import Path

from .emit import (holdout_csv, history_sql, planning_section, read_observations_csv, seed_sql,
                   trace, write_json)
from .fit import FitOptions, fit
from .generate import simulate_period
from .predict import ModelParams
from .report import write_report
from .world import load_world

DEFAULT_WORLD = "worlds/standard.json"
DEFAULT_HISTORY_DAYS = 42            # 6 недель (ml-model.md 6.2)
DEFAULT_SEED = 42

# Эталон для Java-теста `DurationModelTest`: нода обязана считать тот же прогноз, что обучатель.
# Файлы коммитятся в тестовые ресурсы ноды, чтобы Java-тесту не нужен был Python.
FIXTURES_DIR = "../java-backend/node-app/src/test/resources/model"
# Трассы и секция planning едут к разработчику проигрывателя по гиту, поэтому не в build/ (он под
# .gitignore), а в каталог стенда рядом с остальной инфраструктурой демо.
SYNTH_DIR = "../infrastructure/demo/scenarios"
# Поток сценария относительно профиля: 1.0 — день по плану. Вводная прогона, а не константа кода.
DEFAULT_FLOW_BIAS = {"normal": 1.0, "weekend": 1.0, "pension": 1.3}
FIXTURE_DAY = "2026-09-20"
FIXTURE_DAYS = 42
FIXTURE_SEED = 42
FIXTURE_ROWS = 200


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="esuo-ml", description="ЕСУО: синтетика, обучение и отчёт")
    subparsers = parser.add_subparsers(dest="command", required=True)

    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--world", default=DEFAULT_WORLD, help=f"файл мира (по умолчанию {DEFAULT_WORLD})")
    common.add_argument("--seed", type=int, default=DEFAULT_SEED, help="зерно; задаёт весь прогон")
    common.add_argument("--days", type=int, default=DEFAULT_HISTORY_DAYS, help="дней истории")
    common.add_argument("--end-day", default=None, help="последний день истории, ГГГГ-ММ-ДД (по умолчанию вчера)")

    generate = subparsers.add_parser("generate", parents=[common], help="справочники, история, трассы, выборка")
    generate.add_argument("--out", default=SYNTH_DIR, help="каталог для файлов")
    generate.add_argument("--holdout-rows", type=int, default=200, help="строк в читаемой выборке")
    generate.add_argument("--flow-bias", default=None, metavar="СЦЕНАРИЙ=ЧИСЛО,…",
                          help="отклонение фактического потока от профиля, например "
                               "'pension=1.3': день пошёл хуже плана (по умолчанию "
                               + ",".join(f"{s}={b}" for s, b in DEFAULT_FLOW_BIAS.items()) + ")")

    fit_parser = subparsers.add_parser("fit", parents=[common], help="обучить модель")
    fit_parser.add_argument("--out", default="build/model-params.json", help="файл параметров")
    fit_parser.add_argument("--from", dest="source", default=None,
                            help="CSV с наблюдениями вместо синтетики (колонки как в holdout.csv)")
    fit_parser.add_argument("--baseline", default=None, help="прежние параметры для приёмки версии")
    fit_parser.add_argument("--train-days", type=int, default=28)
    fit_parser.add_argument("--holdout-days", type=int, default=3)
    fit_parser.add_argument("--shrinkage-k", type=float, default=10.0)
    fit_parser.add_argument("--accept-mae-ratio", type=float, default=1.05)
    fit_parser.add_argument("--use-window", action="store_true",
                            help="включить поправку на окно (по умолчанию выключена)")
    fit_parser.add_argument("--version", default=None, help="имя версии, по умолчанию dm-ГГГГ-ММ-ДД-01")

    report = subparsers.add_parser("report", parents=[common], help="отчёт для жюри")
    report.add_argument("--out", default="../docs/demo/ml-report.md")
    report.add_argument("--params", default=None, help="готовые параметры; иначе обучает на месте")

    fixtures = subparsers.add_parser("fixtures", parents=[common],
                                     help="эталон для Java-теста: выборка и параметры модели")
    fixtures.add_argument("--out", default=FIXTURES_DIR, help="каталог тестовых ресурсов ноды")
    fixtures.add_argument("--rows", type=int, default=200, help="строк в выборке")

    predict = subparsers.add_parser("predict", help="прогноз одного обслуживания (для отладки)")
    predict.add_argument("--params", required=True)
    predict.add_argument("--service", required=True)
    predict.add_argument("--category", default="default")
    predict.add_argument("--employee", default=None)
    predict.add_argument("--hour", type=int, default=12)
    predict.add_argument("--window", default=None)

    args = parser.parse_args(argv)
    if args.command == "generate":
        return _generate(args)
    if args.command == "fit":
        return _fit(args)
    if args.command == "report":
        return _report(args)
    if args.command == "fixtures":
        return _fixtures(args)
    return _predict(args)


# ---------------------------------------------------------------------- команды


def _end_day(args) -> date:
    return date.fromisoformat(args.end_day) if args.end_day else date.today() - timedelta(days=1)


def _generate(args) -> int:
    world = load_world(args.world)
    # Вводные разбираются до первой записи: опечатка в ключе не должна оставлять каталог
    # наполовину перезаписанным — иначе трассы и planning.json окажутся из разных прогонов.
    flow_bias = _flow_bias(args.flow_bias)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    end_day = _end_day(args)

    observations = simulate_period(world, end_day, args.days, args.seed)
    (out / "seed.sql").write_text(seed_sql(world), encoding="utf-8")
    (out / "history.sql").write_text(history_sql(world, observations, args.seed), encoding="utf-8")
    rows = holdout_csv(observations, out / "holdout.csv", args.holdout_rows)
    write_json(out / "planning.json", planning_section(world, today=end_day))

    for scenario, bias in flow_bias.items():
        day = _scenario_day(world, scenario, end_day)
        day_observations = simulate_period(world, day, 1, args.seed, flow_bias=bias)
        write_json(out / f"scenario-{scenario}.json",
                   trace(world, day, scenario, args.seed, bias, day_observations).to_json())

    print(f"Мир {world.code!r}: {len(observations)} обслуживаний за {args.days} дн. (по {end_day})")
    print(f"  {out/'seed.sql'}        справочники: услуги, окна, сотрудники")
    print(f"  {out/'history.sql'}     история для БД отделения")
    print(f"  {out/'holdout.csv'}     читаемая выборка, {rows} строк")
    print(f"  {out/'planning.json'}   секция planning для публикации в центре")
    print(f"  {out}/scenario-*.json  трассы демо-сценариев")
    print(f"\nПрименить историю: docker exec -i esuo-postgres-b01 psql -U esuo -d esuo < {out/'history.sql'}")
    return 0


def _flow_bias(raw: str | None) -> dict[str, float]:
    """`--flow-bias pension=1.3,normal=1.1` поверх значений по умолчанию."""
    bias = dict(DEFAULT_FLOW_BIAS)
    for part in (raw or "").split(","):
        if not part.strip():
            continue
        scenario, _, value = part.partition("=")
        scenario = scenario.strip()
        if scenario not in bias:
            raise SystemExit(f"--flow-bias: неизвестный сценарий {scenario!r}; "
                             f"допустимы: {', '.join(bias)}")
        try:
            bias[scenario] = float(value)
        except ValueError:
            raise SystemExit(f"--flow-bias: {value!r} не число") from None
        if bias[scenario] <= 0:
            raise SystemExit(f"--flow-bias: {scenario} должен быть больше нуля")
    return bias


def _scenario_day(world, scenario: str, end_day: date) -> date:
    """День, на котором профиль совпадает с названием сценария."""
    from .generate import profile_for

    for offset in range(0, 60):
        day = end_day - timedelta(days=offset)
        if profile_for(world, day)[0] == scenario:
            return day
    return end_day


def _fit(args) -> int:
    world = load_world(args.world)
    if args.source:
        observations = read_observations_csv(Path(args.source))
        origin = args.source
    else:
        observations = simulate_period(world, _end_day(args), args.days, args.seed)
        origin = f"синтетика мира {world.code!r}, seed {args.seed}"

    baseline = None
    if args.baseline and Path(args.baseline).exists():
        baseline = ModelParams.from_section(_read_section(Path(args.baseline)))

    options = FitOptions(train_days=args.train_days, holdout_days=args.holdout_days,
                         shrinkage_k=args.shrinkage_k, accept_mae_ratio=args.accept_mae_ratio,
                         use_window=args.use_window)
    result = fit(observations, options, baseline=baseline, version=args.version)
    section = {"model": result.params.to_section()}

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    write_json(out, section)

    metrics = result.params.metrics
    print(f"Выборка: {origin}")
    print(f"Обучение: {metrics.get('observations', 0)} наблюдений, контроль {metrics.get('holdout_observations', 0)}")
    print(f"Версия {result.params.version}: {result.status} — {result.reason}")
    if "mae_sec" in metrics:
        print(f"  MAE модели (p50)          {metrics['mae_sec']:>8.1f} с")
        print(f"  среднее по услуге         {metrics['baseline_service_mae_sec']:>8.1f} с")
        print(f"  каскад handling_stats     {metrics['baseline_cascade_mae_sec']:>8.1f} с")
        print(f"  константа 300 с           {metrics['baseline_const_mae_sec']:>8.1f} с")
        print(f"  выигрыш к среднему        {metrics['gain_over_service_mean'] * 100:>8.1f} %")
        print(f"  покрытие p90              {metrics['p90_coverage']:>8.2f}")
    print(f"\nЗаписано {out}")
    if result.accepted:
        print("Опубликовать: PUT /config/global (или /config/branches/{b}) с этим телом")
        return 0
    print("Версия не принята, в центр её публиковать нельзя", file=sys.stderr)
    return 1


def _report(args) -> int:
    world = load_world(args.world)
    observations = simulate_period(world, _end_day(args), args.days, args.seed)
    params = None
    if args.params and Path(args.params).exists():
        params = ModelParams.from_section(_read_section(Path(args.params)))
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    write_report(world, observations, out, params=params, seed=args.seed)
    print(f"Отчёт записан: {out}")
    return 0


def build_fixtures(world_path: str = DEFAULT_WORLD) -> tuple[str, list, dict]:
    """Строит эталон детерминированно: фиксированные день, длина истории, зерно и число строк.

    Ничего из аргументов командной строки здесь не участвует сознательно: эталон должен получаться
    одинаковым у любого, кто его пересоберёт, иначе Java-тест начнёт падать от смены параметров запуска.
    """
    world = load_world(world_path)
    observations = simulate_period(world, date.fromisoformat(FIXTURE_DAY), FIXTURE_DAYS, FIXTURE_SEED)
    result = fit(observations, FitOptions(), version="dm-fixture")
    section = result.params.to_section()
    section.pop("trained_at", None)               # иначе эталон менялся бы при каждой пересборке
    return world.code, observations[-FIXTURE_ROWS:], section


def _fixtures(args) -> int:
    _, rows, section = build_fixtures(args.world)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    written = holdout_csv(rows, out / "holdout.csv", limit=0)
    write_json(out / "model-params.json", {"model": section})
    print(f"Эталон для Java-теста записан в {out}:")
    print(f"  holdout.csv        {written} наблюдений")
    print(f"  model-params.json  версия {section['version']}")
    print("\nJava-тест DurationModelTest читает эти файлы и сверяет прогноз ноды с прогнозом обучателя.")
    return 0


def _predict(args) -> int:
    params = ModelParams.from_section(_read_section(Path(args.params)))
    prediction = params.predict(args.service, args.category, args.employee, args.hour, args.window)
    if prediction is None:
        print("Модель не может дать прогноз: запасная цепочка (среднее по услуге, затем 300 с)")
        return 0
    print(json.dumps({
        "mean_sec": round(prediction.mean_sec, 1), "p50_sec": round(prediction.p50_sec, 1),
        "p90_sec": round(prediction.p90_sec, 1), "basis": prediction.basis,
        "model_version": prediction.model_version, "samples": prediction.samples,
    }, ensure_ascii=False, indent=2))
    return 0


def _read_section(path: Path) -> dict:
    """Принимает и полный объект конфигурации `{"model": {...}}`, и саму секцию."""
    payload = json.loads(path.read_text(encoding="utf-8"))
    return payload.get("model", payload)


if __name__ == "__main__":
    raise SystemExit(main())
