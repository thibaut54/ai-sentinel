"""Unit tests for the Precision/Recall/F1 properties added to ``DualMetrics``.

``DualMetrics`` lives in the OpenMed with-judge integration module (it is the
shared metric collector for both the OpenMed and GLiNER2 evals). These tests
only exercise the pure arithmetic of the derived properties — no model, no LM
Studio — by setting the tp/fp/fn counters directly.
"""
import pytest

from tests.integration.test_openmed_realistic_fp_evaluation_with_judge import (
    DualMetrics,
)


def _metrics(b_tp=0, b_fp=0, b_fn=0, j_tp=0, j_fp=0, j_fn=0):
    m = DualMetrics(pii_type="X")
    m.baseline_tp, m.baseline_fp, m.baseline_fn = b_tp, b_fp, b_fn
    m.judged_tp, m.judged_fp, m.judged_fn = j_tp, j_fp, j_fn
    return m


class TestPrecisionRecallF1:
    def test_Should_ComputePrecisionRecallF1_When_CountsNonZero(self):
        # tp=8, fp=2 -> P=0.8 ; tp=8, fn=2 -> R=0.8 ; F1 = 0.8
        m = _metrics(b_tp=8, b_fp=2, b_fn=2)

        assert m.baseline_precision == pytest.approx(0.8)
        assert m.baseline_recall == pytest.approx(0.8)
        assert m.baseline_f1 == pytest.approx(0.8)

    def test_Should_ComputeAsymmetricF1_When_PrecisionDiffersFromRecall(self):
        # tp=6, fp=2 -> P=0.75 ; tp=6, fn=6 -> R=0.5 ; F1 = 2*.75*.5/(1.25)=0.6
        m = _metrics(b_tp=6, b_fp=2, b_fn=6)

        assert m.baseline_precision == pytest.approx(0.75)
        assert m.baseline_recall == pytest.approx(0.5)
        assert m.baseline_f1 == pytest.approx(0.6)

    def test_Should_TrackJudgedSeparately_When_BaselineAndJudgedDiffer(self):
        m = _metrics(b_tp=4, b_fp=4, b_fn=0, j_tp=4, j_fp=0, j_fn=0)

        assert m.baseline_precision == pytest.approx(0.5)
        assert m.judged_precision == pytest.approx(1.0)
        assert m.judged_f1 == pytest.approx(1.0)

    def test_Should_ReturnZero_When_NoPositives(self):
        # No detections and no expected -> P, R, F1 all defined as 0 (no division).
        m = _metrics()

        assert m.baseline_precision == 0.0
        assert m.baseline_recall == 0.0
        assert m.baseline_f1 == 0.0

    def test_Should_ReturnZeroF1_When_PrecisionPlusRecallIsZero(self):
        # Only false positives: P=0, R=0 (no expected) -> F1 must not divide by 0.
        m = _metrics(b_tp=0, b_fp=5, b_fn=0)

        assert m.baseline_precision == 0.0
        assert m.baseline_recall == 0.0
        assert m.baseline_f1 == 0.0

    def test_Should_HandleZeroPrecisionWithPositiveRecall_When_OnlyMisses(self):
        # tp=0, fp=3 -> P=0 ; tp=0, fn=4 -> R=0 ; F1=0. (Guards the P+R=0 branch.)
        m = _metrics(b_tp=0, b_fp=3, b_fn=4)

        assert m.baseline_f1 == 0.0
