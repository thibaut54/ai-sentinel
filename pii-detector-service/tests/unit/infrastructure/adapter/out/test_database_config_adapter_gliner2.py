"""Tests for the GLiNER2 additions to ``DatabaseConfigAdapter`` (spec §4.7).

Covers:
- ``fetch_config`` exposes ``gliner2_enabled`` (true / false / NULL->False) and
  falls back to ``False`` when the column is missing (pre-migration).
- ``fetch_pii_type_configs`` exposes ``detector_description`` and survives a
  missing ``detector_description`` column via the defensive fallback query.
"""
from __future__ import annotations

from contextlib import contextmanager
from typing import Any, Dict, Iterator, List, Optional
from unittest.mock import MagicMock, patch

import psycopg2

from pii_detector.infrastructure.adapter.out.database_config_adapter import (
    DatabaseConfigAdapter,
)


@contextmanager
def _patched_adapter(
    fetchone_results: Optional[List[Optional[Dict[str, Any]]]] = None,
    fetchall_results: Optional[List[List[Dict[str, Any]]]] = None,
    execute_side_effects: Optional[List[Optional[Exception]]] = None,
) -> Iterator[DatabaseConfigAdapter]:
    cursor = MagicMock()
    if fetchone_results is not None:
        fetch_iter = iter(fetchone_results)
        cursor.fetchone.side_effect = lambda: next(fetch_iter)
    if fetchall_results is not None:
        fetchall_iter = iter(fetchall_results)
        cursor.fetchall.side_effect = lambda: next(fetchall_iter)
    if execute_side_effects:
        cursor.execute.side_effect = execute_side_effects

    connection = MagicMock()
    connection.cursor.return_value = cursor
    connection.rollback = MagicMock()

    with patch.object(psycopg2, "connect", return_value=connection):
        yield DatabaseConfigAdapter()


def _config_row(gliner2: Any = False) -> Dict[str, Any]:
    return {
        "gliner_enabled": True,
        "presidio_enabled": True,
        "regex_enabled": False,
        "openmed_enabled": False,
        "gliner2_enabled": gliner2,
        "default_threshold": 0.5,
        "nb_of_label_by_pass": 35,
        "llm_judge_enabled": False,
    }


class TestFetchConfigGliner2Flag:
    def test_should_expose_gliner2_enabled_true(self):
        with _patched_adapter(fetchone_results=[_config_row(gliner2=True)]) as adapter:
            config = adapter.fetch_config()
        assert config["gliner2_enabled"] is True

    def test_should_default_false_when_value_none(self):
        with _patched_adapter(fetchone_results=[_config_row(gliner2=None)]) as adapter:
            config = adapter.fetch_config()
        assert config["gliner2_enabled"] is False

    def test_should_default_false_when_column_missing(self):
        missing = psycopg2.errors.UndefinedColumn("column gliner2_enabled does not exist")
        fallback_row = {
            "gliner_enabled": True, "presidio_enabled": True, "regex_enabled": False,
            "openmed_enabled": False, "gliner2_enabled": False,
            "default_threshold": 0.5, "nb_of_label_by_pass": 35,
        }
        with _patched_adapter(
            fetchone_results=[fallback_row],
            execute_side_effects=[missing, None],
        ) as adapter:
            config = adapter.fetch_config()
        assert config["gliner2_enabled"] is False


class TestFetchPiiTypeConfigsDescription:
    def test_should_expose_detector_description(self):
        rows = [{
            "pii_type": "EMAIL", "detector": "GLINER2", "enabled": True,
            "threshold": 0.5, "category": "CONTACT", "country_code": None,
            "detector_label": "email", "detector_description": "adresse e-mail",
        }]
        with _patched_adapter(fetchall_results=[rows]) as adapter:
            configs = adapter.fetch_pii_type_configs(detector="GLINER2")
        assert configs["EMAIL"]["detector_description"] == "adresse e-mail"

    def test_should_default_description_none_when_column_missing(self):
        """Pre-migration: detector_description absent -> defensive fallback query."""
        missing = psycopg2.errors.UndefinedColumn(
            "column detector_description does not exist"
        )
        fallback_rows = [{
            "pii_type": "EMAIL", "detector": "GLINER2", "enabled": True,
            "threshold": 0.5, "category": "CONTACT", "country_code": None,
            "detector_label": "email",
        }]
        with _patched_adapter(
            fetchall_results=[fallback_rows],
            execute_side_effects=[missing, None],
        ) as adapter:
            configs = adapter.fetch_pii_type_configs(detector="GLINER2")
        assert configs["EMAIL"]["detector_description"] is None
        assert configs["EMAIL"]["detector_label"] == "email"
