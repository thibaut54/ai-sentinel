"""
Tests for :class:`LLMJudgeValidator` (Qwen 3.6 as judge).

Most test classes are placeholders that will be wired in commit 3 of the
``feature/qwen-as-judge`` plan. The skeleton tests below guarantee that
the package imports cleanly and that pytest can collect the suite without
error.
"""

from __future__ import annotations

import pytest

from pii_detector.domain.port.pii_post_filter_protocol import (
    PIIPostFilterProtocol,
)
from pii_detector.infrastructure.validation.llm_validator import (
    LLMJudgeValidator,
    get_instance,
)


# ---------------------------------------------------------------------------
# Skeleton (active in commit 1)
# ---------------------------------------------------------------------------


class TestLLMJudgeValidatorSkeleton:
    """Sanity checks on the no-op skeleton shipped in commit 1."""

    def test_should_implement_port_when_instantiated(self) -> None:
        validator = LLMJudgeValidator()
        assert isinstance(validator, PIIPostFilterProtocol)

    def test_should_return_input_entities_unchanged_when_filter_called(
        self,
    ) -> None:
        validator = LLMJudgeValidator()
        # PIIEntity import is heavy through detector_source -> keep test
        # decoupled by feeding arbitrary placeholders that satisfy the
        # protocol's signature at runtime.
        sentinels = [object(), object(), object()]
        result = validator.filter("any text", sentinels)  # type: ignore[arg-type]
        assert result == sentinels
        # Shallow copy contract: same references, distinct list object.
        assert result is not sentinels

    def test_should_expose_stable_name_when_queried(self) -> None:
        validator = LLMJudgeValidator()
        assert validator.name == "llm-judge-qwen-3.6"

    def test_should_return_same_instance_when_singleton_requested_twice(
        self,
    ) -> None:
        first = get_instance()
        second = get_instance()
        assert first is second


# ---------------------------------------------------------------------------
# Placeholder suites — wired in commit 3
# ---------------------------------------------------------------------------


@pytest.mark.skip(reason="wired in commit 3")
class TestExtractJsonPayload:
    """Defensive parsing of LM Studio + Qwen 3.6 responses (commit 3)."""

    def test_placeholder(self) -> None:  # pragma: no cover
        pass


@pytest.mark.skip(reason="wired in commit 3")
class TestLLMValidatorGracefulDegradation:
    """Fail-open behavior when the LLM is unavailable (commit 3)."""

    def test_placeholder(self) -> None:  # pragma: no cover
        pass


@pytest.mark.skip(reason="wired in commit 3")
class TestLLMValidatorTimeout:
    """Timeout handling on the HTTP path (commit 3)."""

    def test_placeholder(self) -> None:  # pragma: no cover
        pass


@pytest.mark.skip(reason="wired in commit 3")
class TestLLMValidatorSubBatching:
    """Batch sizing and sub-batch handling (commit 3)."""

    def test_placeholder(self) -> None:  # pragma: no cover
        pass


@pytest.mark.skip(reason="wired in commit 3")
class TestLLMValidatorRemoteBackend:
    """LM Studio HTTP backend wiring (commit 3)."""

    def test_placeholder(self) -> None:  # pragma: no cover
        pass


@pytest.mark.skip(reason="local backend hors scope MVP")
class TestLLMValidatorLoadModel:
    """Local (llama-cpp) backend — out of scope for the MVP."""

    def test_placeholder(self) -> None:  # pragma: no cover
        pass
