"""Experimental pre-filter strategy variants (NOT wired into the prod registry).

These live outside :mod:`registry` on purpose: they exist only so the benchmark
TI (``tests/integration/test_format_prefilter_benchmark.py``) can compare an
alternative algorithm against the production rule and produce a reproducible
``coverage / collisions / true_losses`` table. Never import these from the
detection pipeline.
"""
