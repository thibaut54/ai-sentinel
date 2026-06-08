"""Tests for the per-type ``llm_judge_enabled`` flag in ``DatabaseConfigAdapter``.

Covers ``fetch_pii_type_configs``:
- exposes ``llm_judge_enabled`` (true / false / NULL->True);
- survives a missing ``llm_judge_enabled`` column via the defensive
  fallback query (defaults to True, judge enabled).
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
    fetchall_results: Optional[List[List[Dict[str, Any]]]] = None,
    execute_side_effects: Optional[List[Optional[Exception]]] = None,
) -> Iterator[DatabaseConfigAdapter]:
    cursor = MagicMock()
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


def _type_row(llm_judge_enabled: Any = True) -> Dict[str, Any]:
    return {
        "pii_type": "PERSON", "detector": "GLINER", "enabled": True,
        "threshold": 0.5, "category": "IDENTITY", "country_code": None,
        "detector_label": "person", "detector_description": None,
        "llm_judge_enabled": llm_judge_enabled,
    }


class TestFetchPiiTypeConfigsLlmJudgeFlag:
    def test_should_expose_llm_judge_enabled_false(self):
        with _patched_adapter(
            fetchall_results=[[_type_row(llm_judge_enabled=False)]]
        ) as adapter:
            configs = adapter.fetch_pii_type_configs(detector="GLINER")
        assert configs["PERSON"]["llm_judge_enabled"] is False

    def test_should_expose_llm_judge_enabled_true(self):
        with _patched_adapter(
            fetchall_results=[[_type_row(llm_judge_enabled=True)]]
        ) as adapter:
            configs = adapter.fetch_pii_type_configs(detector="GLINER")
        assert configs["PERSON"]["llm_judge_enabled"] is True

    def test_should_default_true_when_value_none(self):
        with _patched_adapter(
            fetchall_results=[[_type_row(llm_judge_enabled=None)]]
        ) as adapter:
            configs = adapter.fetch_pii_type_configs(detector="GLINER")
        assert configs["PERSON"]["llm_judge_enabled"] is True

    def test_should_default_true_when_column_missing(self):
        """Pre-migration: llm_judge_enabled absent -> defensive fallback query."""
        missing = psycopg2.errors.UndefinedColumn(
            "column llm_judge_enabled does not exist"
        )
        fallback_row = _type_row()
        del fallback_row["llm_judge_enabled"]
        with _patched_adapter(
            fetchall_results=[[fallback_row]],
            execute_side_effects=[missing, None],
        ) as adapter:
            configs = adapter.fetch_pii_type_configs(detector="GLINER")
        assert configs["PERSON"]["llm_judge_enabled"] is True
        assert configs["PERSON"]["detector_label"] == "person"
