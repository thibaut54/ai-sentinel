"""Tests for the ``prefilter_enabled`` flag in
:class:`DatabaseConfigAdapter.fetch_config`.

These tests focus narrowly on the new column added for the deterministic
format pre-filter (PLAN.md section 1.7), mirroring the ``llm_judge_enabled``
flag tests:

- Normal path returning the flag from the row.
- Defensive fallback when the column is missing (migration not yet
  applied).
- Normalisation of NULL / missing values into ``False``.
"""

from __future__ import annotations

from contextlib import contextmanager
from typing import Any, Dict, Iterator, List, Optional
from unittest.mock import MagicMock, patch

import psycopg2

from pii_detector.infrastructure.adapter.out.database_config_adapter import (
    DatabaseConfigAdapter,
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _row(
    *,
    gliner: bool = True,
    presidio: bool = True,
    regex: bool = False,
    openmed: bool = False,
    gliner2: bool = False,
    threshold: float = 0.5,
    chunk_size: int = 10,
    llm_judge: Any = False,
    prefilter: Any = False,
) -> Dict[str, Any]:
    # Mirrors what a RealDictCursor returns for the main SELECT (openmed /
    # gliner2 are COALESCEd to FALSE there, so they are always present).
    return {
        "gliner_enabled": gliner,
        "presidio_enabled": presidio,
        "regex_enabled": regex,
        "openmed_enabled": openmed,
        "gliner2_enabled": gliner2,
        "default_threshold": threshold,
        "nb_of_label_by_pass": chunk_size,
        "llm_judge_enabled": llm_judge,
        "prefilter_enabled": prefilter,
    }


@contextmanager
def _patched_adapter(
    fetchone_results: List[Optional[Dict[str, Any]]],
    execute_side_effects: Optional[List[Optional[Exception]]] = None,
) -> Iterator[DatabaseConfigAdapter]:
    """Patch ``psycopg2.connect`` to feed the supplied rows / errors."""
    cursor = MagicMock()
    fetch_iter = iter(fetchone_results)
    cursor.fetchone.side_effect = lambda: next(fetch_iter)
    if execute_side_effects:
        cursor.execute.side_effect = execute_side_effects

    connection = MagicMock()
    connection.cursor.return_value = cursor
    connection.rollback = MagicMock()

    with patch.object(psycopg2, "connect", return_value=connection):
        adapter = DatabaseConfigAdapter()
        yield adapter


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


class TestFetchConfigPrefilterFlag:
    def test_should_return_prefilter_enabled_true_when_db_sets_it(self) -> None:
        with _patched_adapter([_row(prefilter=True)]) as adapter:
            config = adapter.fetch_config()
        assert config is not None
        assert config["prefilter_enabled"] is True

    def test_should_return_false_when_db_sets_false(self) -> None:
        with _patched_adapter([_row(prefilter=False)]) as adapter:
            config = adapter.fetch_config()
        assert config is not None
        assert config["prefilter_enabled"] is False

    def test_should_default_to_false_when_value_is_none(self) -> None:
        with _patched_adapter([_row(prefilter=None)]) as adapter:
            config = adapter.fetch_config()
        assert config is not None
        assert config["prefilter_enabled"] is False

    def test_should_default_to_false_when_column_missing(self) -> None:
        """The pre-migration schema lacks ``prefilter_enabled``.

        The adapter must execute a fallback query without the column and
        normalise the flag to ``False`` so downstream code stays
        compatible with deployments that have not migrated yet.
        """
        missing_column_error = psycopg2.errors.UndefinedColumn(
            "column prefilter_enabled does not exist"
        )
        fallback_row = {
            "gliner_enabled": True,
            "presidio_enabled": True,
            "regex_enabled": False,
            "openmed_enabled": False,
            "gliner2_enabled": False,
            "default_threshold": 0.5,
            "nb_of_label_by_pass": 10,
        }
        with _patched_adapter(
            fetchone_results=[fallback_row],
            execute_side_effects=[missing_column_error, None],
        ) as adapter:
            config = adapter.fetch_config()
        assert config is not None
        assert config["prefilter_enabled"] is False
