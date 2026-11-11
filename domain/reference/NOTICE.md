# FSRS reference and verification

`Fsrs6Scheduler.kt` ports the FSRS-6 scheduling equations and state transitions
from **open-spaced-repetition/py-fsrs v6.3.2**, distributed under the MIT license.
The upstream license is included in `PY_FSRS_LICENSE` in this directory.

Reference: `https://github.com/open-spaced-repetition/py-fsrs/tree/v6.3.2`.
The generator verifies all six downloaded reference Python source files against
hard-coded SHA-256 values before executing them. Those values are also recorded
in the frozen fixture JSON. No application code generates the expected results.

Reproduction (Python 3.10 or later):

```sh
python3.12 -m venv /tmp/lexicon-fsrs-fixture-env
/tmp/lexicon-fsrs-fixture-env/bin/python -m pip install typing_extensions==4.15.0
/tmp/lexicon-fsrs-fixture-env/bin/python domain/reference/generate_fsrs_fixtures.py
./gradlew :domain:test
```

Configuration: default 21 FSRS-6 parameters, desired retention 0.9, learning
steps 60/600 seconds, relearning step 600 seconds, maximum interval 36,500 days,
fuzzing disabled. Intervals use UTC elapsed whole days, matching py-fsrs. The
app has an additional `NEW` state, mapped to the reference's unreviewed Learning
card with step 0. Unknown/new-card retrievability is exposed as `null`, instead
of the reference's sentinel 0. App-only repetitions and lapses are counted
independently of the reference (which does not maintain these counters).

The port rejects non-finite/corrupt states and review timestamps earlier than
the previous review; these are application validation rules, not new FSRS
equations. The v6.3.2 same-day Hard stability floor is retained. Python's
ties-to-even interval rounding is reproduced with `Math.rint`.
