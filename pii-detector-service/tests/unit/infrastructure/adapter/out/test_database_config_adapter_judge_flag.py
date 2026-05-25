"""
Tests for the ``llm_judge_enabled`` flag in
:class:`DatabaseConfigAdapter.fetch_config`.

These tests focus narrowly on the new column added in spec section 2.6:

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
import pytest

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
    threshold: float = 0.5,
    chunk_size: int = 10,
    llm_judge: Any = False,
) -> Dict[str, Any]:
    return {
        "gliner_enabled": gliner,
        "presidio_enabled": presidio,
        "regex_enabled": regex,
        "default_threshold": threshold,
        "nb_of_label_by_pass": chunk_size,
        "llm_judge_enabled": llm_judge,
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

    cursor_cm = MagicMock()
    cursor_cm.__enter__ = MagicMock(return_value=cursor)
    cursor_cm.__exit__ = MagicMock(return_value=False)

    connection = MagicMock()
    connection.cursor.return_value = cursor
    connection.rollback = MagicMock()

    with patch.object(psycopg2, "connect", return_value=connection):
        adapter = DatabaseConfigAdapter()
        yield adapter


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


class TestFetchConfigJudgeFlag:
    def test_should_return_llm_judge_enabled_true_when_db_sets_it(self) -> None:
        with _patched_adapter([_row(llm_judge=True)]) as adapter:
            config = adapter.fetch_config()
        assert config is not None
        assert config["llm_judge_enabled"] is True

    def test_should_return_false_when_db_sets_false(self) -> None:
        with _patched_adapter([_row(llm_judge=False)]) as adapter:
            config = adapter.fetch_config()
        assert config is not None
        assert config["llm_judge_enabled"] is False

    def test_should_default_to_false_when_value_is_none(self) -> None:
        with _patched_adapter([_row(llm_judge=None)]) as adapter:
            config = adapter.fetch_config()
        assert config is not None
        assert config["llm_judge_enabled"] is False

    def test_should_default_to_false_when_column_missing(self) -> None:
        """The pre-migration schema lacks ``llm_judge_enabled``.

        The adapter must execute a fallback query without the column and
        normalise the flag to ``False`` so downstream code stays
        compatible with deployments that have not migrated yet.
        """
        missing_column_error = psycopg2.errors.UndefinedColumn(
            "column llm_judge_enabled does not exist"
        )
        fallback_row = {
            "gliner_enabled": True,
            "presidio_enabled": True,
            "regex_enabled": False,
            "default_threshold": 0.5,
            "nb_of_label_by_pass": 10,
        }
        with _patched_adapter(
            fetchone_results=[fallback_row],
            execute_side_effects=[missing_column_error, None],
        ) as adapter:
            config = adapter.fetch_config()
        assert config is not None
        assert config["llm_judge_enabled"] is False
