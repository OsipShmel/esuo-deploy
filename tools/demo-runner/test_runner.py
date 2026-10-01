#!/usr/bin/env python3
"""Проверки demo-runner без стенда: python3 tools/demo-runner/test_runner.py (из корня репозитория)."""
import os
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
os.environ["REPO"] = str(ROOT)
sys.path.insert(0, str(Path(__file__).resolve().parent))
import runner  # noqa: E402


class ScenariosTest(unittest.TestCase):
    def test_scenarios_are_read_from_scripts(self):
        items = {s["code"]: s for s in runner.scenarios()}
        self.assertIn("normal-day", items)
        self.assertTrue(items["normal-day"]["title"])
        self.assertEqual(items["link-cut"]["kind"], "chaos")
        self.assertTrue(items["pension-ab"]["range"])
        self.assertEqual(items["doctor"]["kind"], "tool")


class ArgvTest(unittest.TestCase):
    def test_known_flags_become_run_sh_arguments(self):
        argv = runner.argv_for("pension-ab", {"fast": True, "rate": 120, "seed": 7, "from": "09:00", "to": "11:30"})
        self.assertEqual(argv[:3], ["bash", str(runner.RUN_SH), "pension-ab"])
        self.assertEqual(argv[3:], ["--fast", "--seed", "7", "--rate", "120", "--from", "09:00", "--to", "11:30"])

    def test_tools_take_no_arguments(self):
        self.assertEqual(runner.argv_for("doctor", {"fast": True})[2:], ["doctor"])

    def test_nothing_unchecked_reaches_the_shell(self):
        bad = [
            ("no-such; rm -rf /", {}),
            ("normal-day", {"seed": "1; reboot"}),
            ("normal-day", {"rate": True}),
            ("normal-day", {"rate": 100000}),
            ("normal-day", {"pace": -1}),
            ("normal-day", {"from": "09:00"}),             # отрезок дня — только pension-ab
            ("pension-ab", {"from": "9:00 --seed 1"}),
            ("normal-day", ["--fast"]),
        ]
        for code, args in bad:
            with self.subTest(code=code, args=args):
                with self.assertRaises(runner.BadRequest):
                    runner.argv_for(code, args)

    def test_unknown_keys_are_ignored(self):
        self.assertEqual(runner.argv_for("normal-day", {"shell": "$(id)"})[2:], ["normal-day"])


class ClassifyTest(unittest.TestCase):
    def test_line_kinds(self):
        cases = {
            "   ✔ центр видит отделение на связи": "pass",
            "   ✘ табло — ждали 200, пришло 503": "fail",
            "2. Обрываем связь отделения с центром": "step",
            "== Итог: Обрыв связи с центром посреди дня ==": "title",
            "   Смотрите: табло: под QR «Через Wi-Fi отделения»": "watch",
            "   · QR → узел, с: 2": "fact",
            "   Ждём, пока центр заметит пропажу heartbeat": "text",
        }
        for text, kind in cases.items():
            with self.subTest(text=text):
                self.assertEqual(runner.classify(text), kind)


class RunTest(unittest.TestCase):
    def test_run_collects_lines_counts_checks_and_finishes(self):
        r = runner.Runner()
        script = "printf '1. шаг\\n   \\033[32m✔ раз\\033[0m\\n   ✘ два\\n'; exit 0"
        run = r.start("stub", ["bash", "-c", script])
        run.process.wait(10)
        with r.cond:
            r.cond.wait_for(lambda: run.state != "running", 10)
        self.assertEqual([l["kind"] for l in run.lines], ["step", "pass", "fail"])
        self.assertEqual(run.lines[1]["text"].strip(), "✔ раз")               # ANSI-цвета убраны
        self.assertEqual((run.passed, run.failed, run.state), (1, 1, "failed"))  # провал проверки важнее кода выхода

    def test_only_one_run_at_a_time(self):
        r = runner.Runner()
        first = r.start("stub", ["bash", "-c", "sleep 30"])
        try:
            self.assertIsNone(r.start("stub", ["bash", "-c", "true"]))
        finally:
            first.process.kill()


if __name__ == "__main__":
    unittest.main(verbosity=2)
