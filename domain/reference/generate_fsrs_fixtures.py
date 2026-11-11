#!/usr/bin/env python3
"""Generate frozen, independent scheduler results with py-fsrs v6.3.2.

Requires Python >=3.10 and typing_extensions==4.15.0. Downloads only the pinned
reference files into a temporary directory and verifies their exact SHA-256.
Run from any directory; writes domain/src/test/resources/fsrs6_reference.json.
"""
import hashlib
import json
import pathlib
import random
import sys
import tempfile
import urllib.request
from datetime import datetime, timedelta, timezone

BASE = "https://raw.githubusercontent.com/open-spaced-repetition/py-fsrs/v6.3.2/fsrs/"
HASHES = {
    "scheduler.py": "dd04d86ba98ee22106f6bbdb7eede00fa3c848dbf15d18e33db4584f333bac46",
    "card.py": "be433000a1b839b222420233b9019e905f88efda3901e2efd51c3fdb5754f0ab",
    "rating.py": "01bc939a22f7e728ebd9a999dda77a9dc2b7fcfec4f392beea4b11ea7019b85e",
    "review_log.py": "79a4afdc25fcf1abf764b50fdce0e52d37fc7656b8894eda6630b60b0ece1047",
    "__init__.py": "79e8c6c2fc444115fbd2a1f5f9d52593d3e3ef81adfaef1d172e663882cd6f87",
    "state.py": "3ed5d9c98cad65f3fd8e3a31d1904eddc88ee3b6a7ead22585d850ce27fc51f4",
}

def millis(dt):
    return int(dt.timestamp() * 1000) if dt else None

def snapshot(card, reps, lapses):
    return {
        "phase": "NEW" if card.last_review is None else card.state.name.upper(),
        "step": card.step if card.last_review else None,
        "stability": card.stability, "difficulty": card.difficulty,
        "dueAt": millis(card.due), "lastReviewAt": millis(card.last_review),
        "reps": reps, "lapses": lapses,
    }

def main():
    with tempfile.TemporaryDirectory(prefix="lexicon-py-fsrs-") as temp:
        package = pathlib.Path(temp) / "fsrs"
        package.mkdir()
        for filename, expected in HASHES.items():
            data = urllib.request.urlopen(BASE + filename, timeout=30).read()
            actual = hashlib.sha256(data).hexdigest()
            if actual != expected:
                raise RuntimeError(f"Reference identity changed: {filename}: {actual}")
            (package / filename).write_bytes(data)
        sys.path.insert(0, temp)
        from fsrs import Card, Rating, Scheduler, State
        scheduler = Scheduler(enable_fuzzing=False)
        start = datetime(2026, 10, 28, tzinfo=timezone.utc)
        fixtures = []

        def trace(name, grades_and_delays):
            card = Card(card_id=1, due=start)
            reps = lapses = 0
            now = start
            for index, (grade, delay) in enumerate(grades_and_delays):
                if delay == "due":
                    now = card.due
                elif delay == "late":
                    now = card.due + timedelta(days=21, hours=3)
                else:
                    now += timedelta(seconds=delay)
                before = snapshot(card, reps, lapses)
                if card.state == State.Review and grade == Rating.Again:
                    lapses += 1
                card, _ = scheduler.review_card(card, grade, now)
                reps += 1
                probes = []
                for seconds in (0, 12 * 3600, 24 * 3600, 30 * 24 * 3600):
                    probe = now + timedelta(seconds=seconds)
                    probes.append({"at": millis(probe), "r": scheduler.get_card_retrievability(card, probe)})
                fixtures.append({
                    "case": f"{name}/{index}", "before": before,
                    "rating": grade.name.upper(), "now": millis(now),
                    "after": snapshot(card, reps, lapses), "probes": probes,
                })

        for first in Rating:
            for second in Rating:
                for third in Rating:
                    trace(f"branch-{first.name}-{second.name}-{third.name}", [(first, 0), (second, "due"), (third, "late")])
        trace("same-day-hard-floor", [(Rating.Easy, 0), (Rating.Hard, 60), (Rating.Good, 60), (Rating.Again, 60), (Rating.Hard, 60), (Rating.Good, "due")])
        trace("late-learning", [(Rating.Again, 0), (Rating.Good, 86_400 * 20), (Rating.Good, "due"), (Rating.Again, "late"), (Rating.Good, "late")])
        trace("maximum-interval", [(Rating.Easy, 0)] + [(Rating.Easy, 86_400 * 200_000)] * 5)
        rng = random.Random(602026)
        for index in range(30):
            trace(f"seeded-{index}", [(rng.choice(list(Rating)), rng.choice([60, 3600, "due", "late"])) for _ in range(16)])
        result = {"reference": "py-fsrs v6.3.2", "source_hashes": HASHES,
                  "configuration": scheduler.to_dict(), "cases": fixtures}
        out = pathlib.Path(__file__).resolve().parent.parent / "src/test/resources/fsrs6_reference.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print(f"Generated {len(fixtures)} independent reference transitions at {out}")

if __name__ == "__main__":
    main()
