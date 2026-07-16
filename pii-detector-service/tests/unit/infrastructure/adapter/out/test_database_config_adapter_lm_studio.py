"""Tests for the ``lm_studio_host`` / ``lm_studio_port`` columns in
:class:`DatabaseConfigAdapter.fetch_config` (migration 014).

The Python detector reads these at scan start (fetch_config_from_db) to target
the operator-configured LM Studio endpoint. Coverage:

- Normal path surfacing host/port from the row.
- NULL / empty normalisation into the documented defaults (localhost:1234).
- Defensive fallback when the columns are missing (migration not applied yet).
"""

from __future__ import annotations

from contextlib import contextmanager
from typing import Any, Dict, Iterator, List, Optional
from unittest.mock import MagicMock, patch

import psycopg2

from pii_detector.infrastructure.adapter.out.database_config_adapter import (
    DatabaseConfigAdapter,
)


def _row(*, host: Any = "localhost", port: Any = 1234) -> Dict[str, Any]:
    # Mirrors what a RealDictCursor returns for the main SELECT.
    return {
        "presidio_enabled": True,
        "regex_enabled": False,
        "ministral_enabled": False,
        "ministral_chunk_size": 2048,
        "ministral_overlap": 410,
        "default_threshold": 0.5,
        "postfilter_enabled": False,
        "lm_studio_host": host,
        "lm_studio_port": port,
    }


@contextmanager
def _patched_adapter(
    fetchone_results: List[Optional[Dict[str, Any]]],
    execute_side_effects: Optional[List[Optional[Exception]]] = None,
) -> Iterator[DatabaseConfigAdapter]:
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


class TestFetchConfigLmStudioEndpoint:
    def test_should_surface_host_and_port_from_row(self) -> None:
        with _patched_adapter([_row(host="192.168.1.20", port=9999)]) as adapter:
            config = adapter.fetch_config()
        assert config is not None
        assert config["lm_studio_host"] == "192.168.1.20"
        assert config["lm_studio_port"] == 9999

    def test_should_default_host_when_none_or_empty(self) -> None:
        with _patched_adapter([_row(host=None)]) as adapter:
            config = adapter.fetch_config()
        assert config is not None
        assert config["lm_studio_host"] == "localhost"

        with _patched_adapter([_row(host="")]) as adapter:
            config = adapter.fetch_config()
        assert config is not None
        assert config["lm_studio_host"] == "localhost"

    def test_should_default_port_when_none(self) -> None:
        with _patched_adapter([_row(port=None)]) as adapter:
            config = adapter.fetch_config()
        assert config is not None
        assert config["lm_studio_port"] == 1234

    def test_should_default_when_columns_missing(self) -> None:
        """Pre-migration schema lacks lm_studio_* -> fallback query + defaults."""
        missing_column_error = psycopg2.errors.UndefinedColumn(
            "column lm_studio_host does not exist"
        )
        # The fallback query in the adapter aliases localhost/1234, but a
        # RealDictCursor mock returns whatever we feed; omit them so the
        # setdefault normalisation is exercised too.
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
        assert config["lm_studio_host"] == "localhost"
        assert config["lm_studio_port"] == 1234
