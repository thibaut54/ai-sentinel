"""Tests for the ``postfilter_enabled`` flag in
:class:`DatabaseConfigAdapter.fetch_config`.

These tests focus narrowly on the ``postfilter_enabled`` column added for the
deterministic format pre-filter (PLAN.md section 1.7):

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
    presidio: bool = True,
    regex: bool = False,
    threshold: float = 0.5,
    postfilter: Any = False,
) -> Dict[str, Any]:
    # Mirrors what a RealDictCursor returns for the main SELECT.
    return {
        "presidio_enabled": presidio,
        "regex_enabled": regex,
        "ministral_enabled": False,
        "ministral_chunk_size": 2048,
        "ministral_overlap": 410,
        "default_threshold": threshold,
        "postfilter_enabled": postfilter,
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
    def test_should_return_postfilter_enabled_true_when_db_sets_it(self) -> None:
        with _patched_adapter([_row(postfilter=True)]) as adapter:
            config = adapter.fetch_config()
        assert config is not None
        assert config["postfilter_enabled"] is True

    def test_should_return_false_when_db_sets_false(self) -> None:
        with _patched_adapter([_row(postfilter=False)]) as adapter:
            config = adapter.fetch_config()
        assert config is not None
        assert config["postfilter_enabled"] is False

    def test_should_default_to_false_when_value_is_none(self) -> None:
        with _patched_adapter([_row(postfilter=None)]) as adapter:
            config = adapter.fetch_config()
        assert config is not None
        assert config["postfilter_enabled"] is False

    def test_should_default_to_false_when_column_missing(self) -> None:
        """The pre-migration schema lacks ``postfilter_enabled``.

        The adapter must execute a fallback query without the column and
        normalise the flag to ``False`` so downstream code stays
        compatible with deployments that have not migrated yet.
        """
        missing_column_error = psycopg2.errors.UndefinedColumn(
            "column postfilter_enabled does not exist"
        )
        fallback_row = {
            "presidio_enabled": True,
            "regex_enabled": False,
            "default_threshold": 0.5,
        }
        with _patched_adapter(
            fetchone_results=[fallback_row],
            execute_side_effects=[missing_column_error, None],
        ) as adapter:
            config = adapter.fetch_config()
        assert config is not None
        assert config["postfilter_enabled"] is False
