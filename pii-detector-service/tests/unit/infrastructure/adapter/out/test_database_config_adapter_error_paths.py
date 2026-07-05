"""
Error-path / edge-case unit tests for :class:`DatabaseConfigAdapter`.

The flag-specific behaviours are covered by the sibling test modules
(``..._prefilter_flag.py``). This
file pins the remaining defensive branches: an empty config row, the
connection / query / unexpected error fallbacks (all returning ``None`` so the
service degrades to TOML defaults), and the composite per-detector key built by
``fetch_pii_type_configs`` when no detector filter is given.

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
) -> Dict[str, Any]:
    return {
        "pii_type": pii_type,
        "detector": detector,
        "enabled": enabled,
        "threshold": threshold,
        "category": category,
        "country_code": country_code,
        "detector_label": detector_label,
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

    def test_Should_NotAddCompositeKey_When_DetectorFilterGiven(self) -> None:
        cursor = MagicMock()
        cursor.fetchall.return_value = [
            _type_row(pii_type="EMAIL", detector="PRESIDIO"),
        ]
        with _patch_connect(_connection_with_cursor(cursor)):
            adapter = DatabaseConfigAdapter()
            configs = adapter.fetch_pii_type_configs(detector="PRESIDIO")

        assert configs is not None
        assert "EMAIL" in configs
        assert "PRESIDIO:EMAIL" not in configs

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
