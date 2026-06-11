"""
Error-path / edge-case unit tests for :class:`DatabaseConfigAdapter`.

The flag-specific behaviours are covered by the sibling test modules
(``..._judge_flag.py``, ``..._prefilter_flag.py``, ``..._gliner2.py``). This
file pins the remaining defensive branches: an empty config row, the
connection / query / unexpected error fallbacks (all returning ``None`` so the
service degrades to TOML defaults), the composite per-detector key built by
``fetch_pii_type_configs`` when no detector filter is given, and the final
``UndefinedColumn`` re-raise when every optional-column fallback fails.

``psycopg2.connect`` is mocked end-to-end; no real database is touched.
"""

from __future__ import annotations

from typing import Any, Dict
from unittest.mock import MagicMock, patch

import psycopg2
import pytest

from pii_detector.infrastructure.adapter.out.database_config_adapter import (
    DatabaseConfigAdapter,
)


def _patch_connect(connection: MagicMock):
    return patch.object(psycopg2, "connect", return_value=connection)


def _connection_with_cursor(cursor: MagicMock) -> MagicMock:
    connection = MagicMock()
    connection.cursor.return_value = cursor
    return connection


class TestFetchConfigErrorPaths:
    def test_Should_ReturnNone_When_NoConfigRow(self) -> None:
        cursor = MagicMock()
        cursor.fetchone.return_value = None
        with _patch_connect(_connection_with_cursor(cursor)):
            adapter = DatabaseConfigAdapter()
            assert adapter.fetch_config() is None

    def test_Should_ReturnNone_When_OperationalError(self) -> None:
        with _patch_connect(MagicMock()) as mock_connect:
            mock_connect.side_effect = psycopg2.OperationalError("conn refused")
            adapter = DatabaseConfigAdapter()
            assert adapter.fetch_config() is None

    def test_Should_ReturnNone_When_GenericDatabaseError(self) -> None:
        cursor = MagicMock()
        cursor.execute.side_effect = psycopg2.Error("query failed")
        with _patch_connect(_connection_with_cursor(cursor)):
            adapter = DatabaseConfigAdapter()
            assert adapter.fetch_config() is None

    def test_Should_ReturnNone_When_UnexpectedError(self) -> None:
        cursor = MagicMock()
        cursor.execute.side_effect = RuntimeError("boom")
        with _patch_connect(_connection_with_cursor(cursor)):
            adapter = DatabaseConfigAdapter()
            assert adapter.fetch_config() is None


def _type_row(
    *,
    pii_type: str,
    detector: str,
    enabled: bool = True,
    threshold: float = 0.5,
    category: str = "CONTACT",
    country_code: str = "CH",
    detector_label: str = "label",
    detector_description: Any = None,
    llm_judge_enabled: Any = None,
) -> Dict[str, Any]:
    return {
        "pii_type": pii_type,
        "detector": detector,
        "enabled": enabled,
        "threshold": threshold,
        "category": category,
        "country_code": country_code,
        "detector_label": detector_label,
        "detector_description": detector_description,
        "llm_judge_enabled": llm_judge_enabled,
    }


class TestFetchPiiTypeConfigs:
    def test_Should_ReturnNone_When_NoRows(self) -> None:
        cursor = MagicMock()
        cursor.fetchall.return_value = []
        with _patch_connect(_connection_with_cursor(cursor)):
            adapter = DatabaseConfigAdapter()
            assert adapter.fetch_pii_type_configs() is None

    def test_Should_AddCompositeKey_When_NoDetectorFilter(self) -> None:
        cursor = MagicMock()
        cursor.fetchall.return_value = [
            _type_row(pii_type="EMAIL", detector="PRESIDIO"),
        ]
        with _patch_connect(_connection_with_cursor(cursor)):
            adapter = DatabaseConfigAdapter()
            configs = adapter.fetch_pii_type_configs(detector=None)

        assert configs is not None
        # Primary key plus the unambiguous composite "DETECTOR:TYPE" key.
        assert "EMAIL" in configs
        assert "PRESIDIO:EMAIL" in configs
        assert configs["EMAIL"] is configs["PRESIDIO:EMAIL"]
        # llm_judge_enabled defaults to True when the DB value is NULL.
        assert configs["EMAIL"]["llm_judge_enabled"] is True

    def test_Should_NotAddCompositeKey_When_DetectorFilterGiven(self) -> None:
        cursor = MagicMock()
        cursor.fetchall.return_value = [
            _type_row(pii_type="EMAIL", detector="PRESIDIO", llm_judge_enabled=False),
        ]
        with _patch_connect(_connection_with_cursor(cursor)):
            adapter = DatabaseConfigAdapter()
            configs = adapter.fetch_pii_type_configs(detector="PRESIDIO")

        assert configs is not None
        assert "EMAIL" in configs
        assert "PRESIDIO:EMAIL" not in configs
        assert configs["EMAIL"]["llm_judge_enabled"] is False

    def test_Should_ReturnNone_When_OperationalError(self) -> None:
        with _patch_connect(MagicMock()) as mock_connect:
            mock_connect.side_effect = psycopg2.OperationalError("conn refused")
            adapter = DatabaseConfigAdapter()
            assert adapter.fetch_pii_type_configs() is None

    def test_Should_ReturnNone_When_GenericDatabaseError(self) -> None:
        cursor = MagicMock()
        cursor.execute.side_effect = psycopg2.Error("query failed")
        with _patch_connect(_connection_with_cursor(cursor)):
            adapter = DatabaseConfigAdapter()
            assert adapter.fetch_pii_type_configs() is None

    def test_Should_ReturnNone_When_UnexpectedError(self) -> None:
        cursor = MagicMock()
        cursor.execute.side_effect = RuntimeError("boom")
        with _patch_connect(_connection_with_cursor(cursor)):
            adapter = DatabaseConfigAdapter()
            assert adapter.fetch_pii_type_configs() is None


class TestExecutePiiTypeQueryFallback:
    def test_Should_ReRaiseUndefinedColumn_When_AllFallbacksExhausted(self) -> None:
        # Every attempt (full, then fewer optional columns) hits UndefinedColumn;
        # the last one must propagate instead of being swallowed.
        cursor = MagicMock()
        cursor.execute.side_effect = psycopg2.errors.UndefinedColumn("missing col")
        connection = _connection_with_cursor(cursor)
        with _patch_connect(connection):
            adapter = DatabaseConfigAdapter()
            # The intermediate fallbacks log a warning before retrying, so the
            # logger must exist (fetch_pii_type_configs sets it in production).
            adapter.logger = MagicMock()
            with pytest.raises(psycopg2.errors.UndefinedColumn):
                adapter._execute_pii_type_query(cursor, connection, detector=None)

    def test_Should_RetryWithFewerColumns_When_FirstAttemptMissesColumn(self) -> None:
        # First execute raises UndefinedColumn, the retry succeeds: the adapter
        # rolls back and returns the fallback rows.
        cursor = MagicMock()
        fallback_rows = [_type_row(pii_type="EMAIL", detector="GLINER")]
        cursor.execute.side_effect = [
            psycopg2.errors.UndefinedColumn("detector_description missing"),
            None,
        ]
        cursor.fetchall.return_value = fallback_rows
        connection = _connection_with_cursor(cursor)
        with _patch_connect(connection):
            adapter = DatabaseConfigAdapter()
            # fetch_pii_type_configs assigns self.logger before delegating; the
            # retry branch logs a warning, so set it here for the direct call.
            adapter.logger = MagicMock()
            rows = adapter._execute_pii_type_query(cursor, connection, detector="GLINER")

        assert rows == fallback_rows
        connection.rollback.assert_called_once()
