"""Benchmark / anti-regression TI for the deterministic format pre-filter (T4).

In-process, no gRPC, no model: this test replays the production GO strategies
(:mod:`pii_detector.infrastructure.postfilter.registry`) over two datasets and
asserts the recall-safety invariants documented in
``my-files/prefilter-work/PLAN.md`` 4 and ``data-analysis.md``.

Datasets
--------
(a) Golden ground truth (always present, committed):
    ``tests/integration/fixtures/judge_findings.jsonl`` -- 144 rows, each with a
    ``ground_truth`` in {TP, FP}, a ``pii_type`` and a ``text``. Hard invariant:
    no TP is ever rejected by a GO strategy (normalisation identical to prod via
    ``_normalize_pii_type``). The golden set covers types without any GO strategy
    (BITCOIN_ADDRESS, IMEI, CREDIT_CARD, BANK_ACCOUNT, ...); those fall through by
    default, which is the expected fail-open behaviour.

(b) Corpus proxy (skipped if ``pii-reporting-api/target/...`` is absent, e.g. CI
    without ``target/``):
    ``judge-discards.jsonl`` (FP proxy) and ``findings.jsonl`` (TP proxy), loaded
    via the robust ``_corpus_io`` loader (lone-CR safe). Measures coverage
    (discards rejected) and collisions (findings rejected) per type and asserts
    the measured floors.

Coverage floors are FLOORS, not exact equalities: a strategy that fails open on
more noisy fragments only *keeps more* entities (never loses recall), so the
assertion is ``coverage >= floor``. See the note on IP_ADDRESS below.

Run (from ``pii-detector-service/``)::

    python -m pytest tests/integration/test_format_prefilter_benchmark.py \\
        -s -o addopts="" -p no:cacheprovider -q
"""

from __future__ import annotations

import ipaddress
import json
from collections import Counter
from pathlib import Path

import pytest

from pii_detector.infrastructure.postfilter.format_postfilter_validator import (
    _normalize_pii_type,
)
from pii_detector.infrastructure.postfilter.registry import STRATEGIES
from pii_detector.infrastructure.postfilter.strategies._experiments.mac_six_octet_hex import (  # noqa: E501
    MacSixOctetHexStrategy,
)
from pii_detector.infrastructure.postfilter.strategies.mac_address import (
    MacAddressStrategy,
)

import tests.integration._corpus_io as corpus_io

# --------------------------------------------------------------------------- #
# Dataset paths
# --------------------------------------------------------------------------- #

_HERE = Path(__file__).resolve().parent
GOLDEN = _HERE / "fixtures" / "judge_findings.jsonl"
DISCARDS = corpus_io.DISCARDS
FINDINGS = corpus_io.FINDINGS

_CORPUS_PRESENT = DISCARDS.exists() and FINDINGS.exists()
_skip_no_corpus = pytest.mark.skipif(
    not _CORPUS_PRESENT,
    reason=f"corpus proxy absent ({DISCARDS.parent}) -- CI without target/",
)

# Measured floors on this corpus (DGNSI/Vaud, regenerated 2026-06-07).
# IP_ADDRESS: 518 discards total, of which 9 are unparsable fragments
# ('ric Cuend', base64 blobs, ...) correctly KEPT by fail-open -> 509 rejected.
# data-analysis.md quotes 518/518 on an earlier corpus snapshot; the extra 9
# fail-open fragments are expected (recall-safe), so we assert the FLOOR 509.
COVERAGE_FLOORS = {"IP_ADDRESS": 509, "MAC_ADDRESS": 12}
# Raw IP collisions (findings rejected) expected on this corpus; all are
# mis-typed AVS / log dates, none a real IPv4 -> see IP_TRUE_LOSSES == 0.
IP_RAW_COLLISIONS_EXPECTED = 36

# -- v2 PRUDENCE floors (CARD_NUMBER + AVS_NUMBER, data-analysis.md) --------- #
# CARD_NUMBER: 21 discards, 3 of which are 14-digit Luhn-invalid numbers the
# judge also rejects -> coverage >= 3 (FLOOR: fail-open on extra noise only
# keeps more, never loses recall). The 9 finding collisions are log/session
# numbers (33333333333331, the 150171107153003 family) that fail Luhn -> they
# are rejected, but none is a real card (verified non-card, 0 recall loss).
CARD_COVERAGE_FLOOR = 3
# AVS_NUMBER: 0 discards rejected -- the 5 discards all carry a VALID EAN-13
# key (the judge rejected them for the wrong reason: it expects a 9-digit AVS).
# Asserting == 0 documents that the format pre-filter cannot and must not catch
# them: they are well-formed AVS-13.
AVS_COVERAGE_EXPECTED = 0
# AVS collisions are EXCLUSIVELY synthetic test AVS injected in the corpus; no
# real AVS is ever rejected. The pre-filter only trips on these test keys.
AVS_TEST_COLLISION_VALUES = {"756.1234.1234.12", "756.1234.1234.14"}

# -- v2b floors (alias keys: TAX_ID/TAX_NUMBER/PAYMENT_CARD/NATIONAL_ID_NUMBER)
# These 4 registry keys are resolved by the registry KEY (the prod lookup in
# format_prefilter_validator), NOT by strategy.pii_type -- TAX_NUMBER /
# PAYMENT_CARD / NATIONAL_ID_NUMBER are pure aliases whose target instance
# carries a different pii_type. We therefore measure them with
# ``_measure_by_key`` below, filtering corpus rows on the normalised
# ``piiTypeDetected`` == registry key.
#
# Ground truth measured on this corpus (DGNSI/Vaud, 2026-06-07):
#   TAX_ID            35 discards / 3 findings -- 0 row matches the strict CHE+9
#                     UID shape, so coverage == 0 AND collisions == 0. The 35
#                     discards (TX940016, 20000596, ...) are foreign / out-of-
#                     template tax ids that fail open. Measured FP gain = nil;
#                     SwissUidStrategy is prod protection only (see its docstring).
#   TAX_NUMBER         2 discards / 0 findings -- same SwissUid instance, neither
#                     discard is CHE+9-shaped -> coverage == 0, collisions == 0.
#   PAYMENT_CARD       0 discards / 0 findings -- 0 corpus occurrence at all, so
#                     the CARD_NUMBER alias trivially scores 0/0 (safe by
#                     construction: digits-only 13-19 + Luhn KO only).
#   NATIONAL_ID_NUMBER 16 discards / 12 findings -- coverage == 0 (no discard is
#                     AVS-756-13-shaped) and collisions == 0. In particular the
#                     2 AVS-756-13 findings (756.3865.9392.03, 756.1801.8116.75)
#                     carry a VALID EAN-13 key -> PASS, and the 10 "56.xxxx"
#                     fragments are 12-digit out-of-template -> PASS.
# All four are EXACT equalities (== 0): the alias keys catch nothing on this
# corpus, exactly as designed (0 FP gain, 0 recall loss).
V2B_ALIAS_KEYS = ("TAX_ID", "TAX_NUMBER", "PAYMENT_CARD", "NATIONAL_ID_NUMBER")
V2B_COVERAGE_EXPECTED = {
    "TAX_ID": 0,
    "TAX_NUMBER": 0,
    "PAYMENT_CARD": 0,
    "NATIONAL_ID_NUMBER": 0,
}
V2B_COLLISIONS_EXPECTED = {
    "TAX_ID": 0,
    "TAX_NUMBER": 0,
    "PAYMENT_CARD": 0,
    "NATIONAL_ID_NUMBER": 0,
}
# The 2 AVS-756-13 findings under NATIONAL_ID_NUMBER that MUST pass (valid key).
NATIONAL_ID_VALID_AVS_FINDINGS = {"756.3865.9392.03", "756.1801.8116.75"}


# --------------------------------------------------------------------------- #
# Loaders
# --------------------------------------------------------------------------- #


def _load_golden():
    return [
        json.loads(line)
        for line in GOLDEN.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]


def _corpus_type(row) -> str:
    """Normalised pii_type of a corpus row (field ``piiTypeDetected``)."""
    return _normalize_pii_type(row.get("piiTypeDetected"))


def _measure(rows, strategy) -> int:
    """Count rows of ``strategy.pii_type`` that ``strategy`` rejects."""
    rejected = 0
    for row in rows:
        if _corpus_type(row) != strategy.pii_type:
            continue
        if not strategy.evaluate(str(row.get("value"))).keep:
            rejected += 1
    return rejected


def _collision_values(rows, strategy) -> list:
    """Values of ``strategy.pii_type`` findings that ``strategy`` would reject."""
    out = []
    for row in rows:
        if _corpus_type(row) != strategy.pii_type:
            continue
        if not strategy.evaluate(str(row.get("value"))).keep:
            out.append(str(row.get("value")))
    return out


def _reject_values_by_key(rows, key: str) -> list:
    """Values of rows typed ``key`` rejected by ``STRATEGIES[key]``.

    Mirrors the production lookup in ``format_prefilter_validator``: rows are
    filtered on the normalised ``piiTypeDetected`` == the REGISTRY KEY, then the
    strategy registered under that exact key decides. This is the only correct
    way to measure an alias key (TAX_NUMBER / PAYMENT_CARD / NATIONAL_ID_NUMBER),
    whose target instance carries a different ``strategy.pii_type``.
    """
    strategy = STRATEGIES[key]
    out = []
    for row in rows:
        if _corpus_type(row) != key:
            continue
        if not strategy.evaluate(str(row.get("value"))).keep:
            out.append(str(row.get("value")))
    return out


def _is_real_ipv4_or_ipv6(value: str) -> bool:
    """True iff ``value`` re-parses as a syntactically valid IPv4/IPv6.

    Used to separate IP *raw collisions* (a finding the rule rejects) from a
    *true recall loss* (the value is in fact a parseable IP address). On this
    corpus every IP collision is a mis-typed AVS / log date, none re-parses.
    """
    try:
        ipaddress.ip_address(value.strip())
        return True
    except (ValueError, AttributeError):
        return False


# --------------------------------------------------------------------------- #
# (a) Golden invariant: no TP rejected by a GO strategy
# --------------------------------------------------------------------------- #


def test_golden_no_true_positive_rejected():
    """No golden TP is rejected by any GO strategy (fail-open recall guard)."""
    rows = _load_golden()
    assert rows, "golden fixture is empty"

    rejected_tp = []
    for row in rows:
        if row["ground_truth"] != "TP":
            continue
        strategy = STRATEGIES.get(_normalize_pii_type(row["pii_type"]))
        if strategy is None:
            continue  # type not in scope -> passes by default (expected)
        if not strategy.evaluate(row["text"]).keep:
            rejected_tp.append((row["finding_id"], row["text"]))

    mapped = {
        _normalize_pii_type(r["pii_type"]) for r in rows
    } & set(STRATEGIES)
    print(
        f"\n[golden] rows={len(rows)} "
        f"types={sorted({r['pii_type'] for r in rows})}"
    )
    print(
        f"[golden] types mapped to a GO strategy={sorted(mapped) or 'none'} "
        f"(others pass by default, expected)"
    )
    assert not rejected_tp, f"GO strategy rejected golden TP(s): {rejected_tp}"


# --------------------------------------------------------------------------- #
# (b) Corpus coverage / collisions per type
# --------------------------------------------------------------------------- #


@_skip_no_corpus
def test_corpus_coverage_and_collisions():
    """Measure coverage + collisions per type; assert recall-safety floors."""
    discards = corpus_io.load(DISCARDS)
    findings = corpus_io.load(FINDINGS)
    assert discards and findings, "corpus loaded empty"

    coverage = {pt: _measure(discards, s) for pt, s in STRATEGIES.items()}
    collisions = {pt: _measure(findings, s) for pt, s in STRATEGIES.items()}

    # IP: split raw collisions from true recall losses (re-parsable IP).
    ip_strategy = STRATEGIES["IP_ADDRESS"]
    ip_collision_vals = _collision_values(findings, ip_strategy)
    ip_true_losses = [v for v in ip_collision_vals if _is_real_ipv4_or_ipv6(v)]

    discard_totals = Counter(_corpus_type(r) for r in discards)
    finding_totals = Counter(_corpus_type(r) for r in findings)

    # ---- full table (visible with -s) ----
    print("\n========== FORMAT PRE-FILTER CORPUS BENCHMARK ==========")
    print(f"discards={len(discards)}  findings={len(findings)}")
    print(
        f"{'pii_type':<13} {'coverage':>9} {'/disc':>6} "
        f"{'collisions':>11} {'/find':>6}"
    )
    for pt in STRATEGIES:
        print(
            f"{pt:<13} {coverage[pt]:>9} {discard_totals.get(pt, 0):>6} "
            f"{collisions[pt]:>11} {finding_totals.get(pt, 0):>6}"
        )
    print(
        f"\n[IP_ADDRESS] raw collisions={len(ip_collision_vals)} "
        f"true recall losses={len(ip_true_losses)}"
    )
    print("[IP_ADDRESS] collision values (none re-parses as a real IP):")
    for v in ip_collision_vals:
        print(f"    {v!r}")
    print("========================================================")

    # ---- recall-safety asserts ----
    # Coverage floors (>= so fail-open on extra noise never trips the test).
    assert coverage["IP_ADDRESS"] >= COVERAGE_FLOORS["IP_ADDRESS"], (
        f"IP coverage regressed: {coverage['IP_ADDRESS']} "
        f"< floor {COVERAGE_FLOORS['IP_ADDRESS']}"
    )
    assert coverage["MAC_ADDRESS"] == COVERAGE_FLOORS["MAC_ADDRESS"], (
        f"MAC coverage changed: {coverage['MAC_ADDRESS']} "
        f"!= {COVERAGE_FLOORS['MAC_ADDRESS']}"
    )
    # Zero collisions = zero risk for MAC and IBAN.
    assert collisions["MAC_ADDRESS"] == 0, (
        f"MAC collisions must be 0, got {collisions['MAC_ADDRESS']}"
    )
    assert collisions["IBAN"] == 0, (
        f"IBAN collisions must be 0, got {collisions['IBAN']}"
    )
    # IP: raw collisions allowed (mis-typed AVS / dates) but ZERO true loss.
    assert len(ip_true_losses) == 0, (
        f"IP true recall loss(es): {ip_true_losses}"
    )
    assert len(ip_collision_vals) == IP_RAW_COLLISIONS_EXPECTED, (
        f"IP raw collisions changed: {len(ip_collision_vals)} "
        f"!= {IP_RAW_COLLISIONS_EXPECTED} (inspect new values, confirm 0 real IP)"
    )


# --------------------------------------------------------------------------- #
# (b2) v2 PRUDENCE coverage / collisions (CARD_NUMBER + AVS_NUMBER)
# --------------------------------------------------------------------------- #


@_skip_no_corpus
def test_corpus_card_number_coverage_and_collisions():
    """CARD_NUMBER: coverage >= floor, every collision a verified non-card."""
    from stdnum import luhn  # deterministic, no I/O

    discards = corpus_io.load(DISCARDS)
    findings = corpus_io.load(FINDINGS)

    card = STRATEGIES["CARD_NUMBER"]
    coverage = _measure(discards, card)
    coverage_vals = _collision_values(discards, card)
    collision_vals = _collision_values(findings, card)

    card_disc_total = sum(
        1 for r in discards if _corpus_type(r) == "CARD_NUMBER"
    )
    card_find_total = sum(
        1 for r in findings if _corpus_type(r) == "CARD_NUMBER"
    )

    print("\n========== CARD_NUMBER (v2 PRUDENCE) ==========")
    print(
        f"discards(total={card_disc_total}) coverage={coverage} "
        f">= floor {CARD_COVERAGE_FLOOR}"
    )
    print("coverage values (14-digit Luhn-invalid, the judge also rejects):")
    for v in coverage_vals:
        print(f"    {v!r}")
    print(
        f"findings(total={card_find_total}) collisions={len(collision_vals)} "
        f"(verified non-cards: log/session numbers that fail Luhn by chance)"
    )
    for v, n in sorted(Counter(collision_vals).items()):
        print(f"    {n}x {v!r}")
    print("===============================================")

    # FLOOR: fail-open on extra noisy fragments only keeps more -> >=.
    assert coverage >= CARD_COVERAGE_FLOOR, (
        f"CARD coverage regressed: {coverage} < floor {CARD_COVERAGE_FLOOR}"
    )
    # Every collision must be a non-card (digits-only, Luhn-invalid by chance,
    # never a real PAN). Re-assert none re-validates as a Luhn-clean PAN: the
    # rule rejects ONLY Luhn-invalid values, so by construction a rejected
    # finding can never be a valid card. We additionally dump them for review.
    for value in collision_vals:
        digits = "".join(ch for ch in value if ch.isdigit())
        assert not luhn.is_valid(digits), (
            f"CARD collision {value!r} is Luhn-valid -- would be a real card!"
        )


@_skip_no_corpus
def test_corpus_avs_number_coverage_and_collisions():
    """AVS_NUMBER: 0 coverage (valid EAN-13 keys), collisions only test AVS."""
    discards = corpus_io.load(DISCARDS)
    findings = corpus_io.load(FINDINGS)

    avs = STRATEGIES["AVS_NUMBER"]
    coverage = _measure(discards, avs)
    collision_vals = _collision_values(findings, avs)

    avs_disc_total = sum(
        1 for r in discards if _corpus_type(r) == "AVS_NUMBER"
    )
    avs_find_total = sum(
        1 for r in findings if _corpus_type(r) == "AVS_NUMBER"
    )

    print("\n========== AVS_NUMBER (v2 PRUDENCE) ==========")
    print(
        f"discards(total={avs_disc_total}) coverage={coverage} "
        f"(== {AVS_COVERAGE_EXPECTED}: the 5 discards all carry a VALID "
        f"EAN-13 key -- judge mis-rejected them, format cannot catch them)"
    )
    print(
        f"findings(total={avs_find_total}) collisions={len(collision_vals)} "
        f"(synthetic test AVS only, no real AVS rejected)"
    )
    for v, n in sorted(Counter(collision_vals).items()):
        print(f"    {n}x {v!r}")
    print("==============================================")

    # Coverage MUST be exactly 0: the 5 AVS discards have valid EAN-13 keys, so
    # a format pre-filter cannot (and must not) reject them. A non-zero value
    # would mean we started rejecting well-formed AVS-13 -> recall risk.
    assert coverage == AVS_COVERAGE_EXPECTED, (
        f"AVS coverage must be {AVS_COVERAGE_EXPECTED} (valid EAN-13 keys), "
        f"got {coverage}"
    )
    # Collisions are exclusively the synthetic test AVS injected in the corpus.
    # Assert no OTHER value is ever rejected (would be a real AVS recall loss).
    rejected_outside_test = sorted(
        {v for v in collision_vals if v not in AVS_TEST_COLLISION_VALUES}
    )
    assert not rejected_outside_test, (
        f"AVS rejected non-test value(s) -- real AVS recall loss: "
        f"{rejected_outside_test}"
    )
    # And confirm the test values are actually present (the safety net trips).
    assert set(collision_vals) <= AVS_TEST_COLLISION_VALUES


# --------------------------------------------------------------------------- #
# (b2b) v2b alias-key coverage / collisions (resolved by REGISTRY KEY)
# --------------------------------------------------------------------------- #


@_skip_no_corpus
def test_corpus_v2b_alias_keys_coverage_and_collisions():
    """v2b keys (TAX_ID/TAX_NUMBER/PAYMENT_CARD/NATIONAL_ID_NUMBER): 0 cov / 0 coll.

    Resolved by the REGISTRY KEY (the prod path), so the alias keys exercise
    their target instance under their own ``piiTypeDetected``. Every key catches
    nothing on this corpus -- 0 FP gain, 0 recall loss -- and the 2 valid
    AVS-756-13 findings typed NATIONAL_ID_NUMBER must PASS.
    """
    discards = corpus_io.load(DISCARDS)
    findings = corpus_io.load(FINDINGS)

    coverage = {k: _reject_values_by_key(discards, k) for k in V2B_ALIAS_KEYS}
    collisions = {k: _reject_values_by_key(findings, k) for k in V2B_ALIAS_KEYS}
    disc_totals = Counter(_corpus_type(r) for r in discards)
    find_totals = Counter(_corpus_type(r) for r in findings)

    print("\n========== v2b ALIAS KEYS (resolved by registry key) ==========")
    print(
        f"{'key':<20} {'cov':>4} {'/disc':>6} {'coll':>5} {'/find':>6}"
    )
    for k in V2B_ALIAS_KEYS:
        print(
            f"{k:<20} {len(coverage[k]):>4} {disc_totals.get(k, 0):>6} "
            f"{len(collisions[k]):>5} {find_totals.get(k, 0):>6}"
        )
    print("(every value rejected, if any, is dumped below)")
    for k in V2B_ALIAS_KEYS:
        for v in coverage[k] + collisions[k]:
            print(f"    [{k}] {v!r}")
    # The 2 valid AVS-756-13 findings typed NATIONAL_ID_NUMBER that MUST pass.
    nat_avs_present = {
        str(r.get("value"))
        for r in findings
        if _corpus_type(r) == "NATIONAL_ID_NUMBER"
        and str(r.get("value")) in NATIONAL_ID_VALID_AVS_FINDINGS
    }
    print(
        f"[NATIONAL_ID_NUMBER] valid AVS findings kept (PASS): "
        f"{sorted(nat_avs_present)}"
    )
    print("===============================================================")

    # ---- recall-safety asserts (EXACT == 0 on every alias key) ----
    for k in V2B_ALIAS_KEYS:
        assert len(coverage[k]) == V2B_COVERAGE_EXPECTED[k], (
            f"{k} coverage changed: {len(coverage[k])} "
            f"!= {V2B_COVERAGE_EXPECTED[k]} -- values: {coverage[k]}"
        )
        assert len(collisions[k]) == V2B_COLLISIONS_EXPECTED[k], (
            f"{k} collisions changed: {len(collisions[k])} "
            f"!= {V2B_COLLISIONS_EXPECTED[k]} -- values: {collisions[k]}"
        )
    # In particular the 2 valid AVS-756-13 findings must be present AND pass:
    # they are the corpus proof that NATIONAL_ID_NUMBER never rejects a valid key.
    assert nat_avs_present == NATIONAL_ID_VALID_AVS_FINDINGS, (
        f"expected the 2 valid AVS findings under NATIONAL_ID_NUMBER, "
        f"got {sorted(nat_avs_present)}"
    )


# --------------------------------------------------------------------------- #
# (b3) Golden ground truth for the Luhn rule (CREDIT_CARD)
# --------------------------------------------------------------------------- #
#
# The golden judge_findings.jsonl carries 6 TP + 6 FP of type CREDIT_CARD
# (OpenMed). The prod registry has NO CREDIT_CARD strategy (the GLiNER2 label
# normalises to CARD_NUMBER, not CREDIT_CARD), so these rows would fall open in
# the pipeline. We therefore apply CardNumberStrategy DIRECTLY -- without
# touching the registry -- to exercise the Luhn rule against real ground truth:
# no TP (including the test card 4242...) may ever be rejected.


def test_golden_credit_card_card_number_strategy():
    """CardNumberStrategy never rejects a golden CREDIT_CARD TP (Luhn ground truth)."""
    from pii_detector.infrastructure.postfilter.strategies.card_number import (
        CardNumberStrategy,
    )

    rows = [r for r in _load_golden() if r["pii_type"] == "CREDIT_CARD"]
    assert rows, "golden has no CREDIT_CARD rows"

    strategy = CardNumberStrategy()
    rejected_tp = []
    fp_caught = []
    fp_total = 0
    for row in rows:
        verdict = strategy.evaluate(row["text"])
        if row["ground_truth"] == "TP" and not verdict.keep:
            rejected_tp.append((row["finding_id"], row["text"]))
        if row["ground_truth"] == "FP":
            fp_total += 1
            if not verdict.keep:
                fp_caught.append((row["finding_id"], row["text"]))

    tp_total = sum(1 for r in rows if r["ground_truth"] == "TP")
    print("\n========== GOLDEN CREDIT_CARD (Luhn ground truth) ==========")
    print(f"TP rows={tp_total}  rejected_TP={len(rejected_tp)} (must be 0)")
    print(
        f"FP rows={fp_total}  caught_by_Luhn={len(fp_caught)} "
        f"(indicative coverage = {len(fp_caught)}/{fp_total})"
    )
    print("FP caught by the Luhn rule:")
    for fid, text in fp_caught:
        print(f"    {text!r}  ({fid})")
    kept_fp = [
        (r["finding_id"], r["text"])
        for r in rows
        if r["ground_truth"] == "FP" and strategy.evaluate(r["text"]).keep
    ]
    print("FP kept (fail-open: non purely-numeric after strip, e.g. 'EP ...'):")
    for fid, text in kept_fp:
        print(f"    {text!r}  ({fid})")
    print("============================================================")

    # HARD invariant: no TP rejected (incl. the canonical test card 4242...).
    assert not rejected_tp, (
        f"CardNumberStrategy rejected golden CREDIT_CARD TP(s): {rejected_tp}"
    )


# --------------------------------------------------------------------------- #
# (c) Variant comparison harness (PLAN.md 4.3)
# --------------------------------------------------------------------------- #
#
# To add a variant: write it in strategies/_experiments/, import it, and add a
# (label, strategy, asserts_prod_floor) tuple to _MAC_VARIANTS below. The test
# prints coverage / collisions / true_losses for every variant but only asserts
# on the production variant -- the experiment row is a reproducible
# demonstration (the 6-hex-octet rule scores 0/12, never failing the suite).

_MAC_VARIANTS = [
    ("prod: consistent-separator", MacAddressStrategy(), True),
    ("experiment: 6-hex-octets (TRAP)", MacSixOctetHexStrategy(), False),
]


@_skip_no_corpus
@pytest.mark.parametrize(
    "label,strategy,is_prod",
    _MAC_VARIANTS,
    ids=[v[0] for v in _MAC_VARIANTS],
)
def test_mac_variant_comparison(label, strategy, is_prod):
    """Compare MAC variants; assert only on the prod rule (experiment is a demo)."""
    discards = corpus_io.load(DISCARDS)
    findings = corpus_io.load(FINDINGS)

    coverage = _measure(discards, strategy)
    collision_vals = _collision_values(findings, strategy)
    # A MAC "true loss" = a rejected finding that is a canonical single-separator
    # MAC (would be a real address). Time ranges mix ':' and '-', so they are
    # never canonical MACs -> 0 true loss either way; we report it for symmetry.
    true_losses = [
        v
        for v in collision_vals
        if (":" in v) ^ ("-" in v)  # exactly one separator family
    ]
    mac_total = sum(
        1 for r in discards if _corpus_type(r) == "MAC_ADDRESS"
    )

    print(
        f"\n[MAC variant] {label:<32} "
        f"coverage={coverage}/{mac_total} "
        f"collisions={len(collision_vals)} "
        f"true_losses={len(true_losses)}"
    )

    if is_prod:
        assert coverage == 12, f"prod MAC coverage regressed: {coverage}"
        assert not collision_vals, f"prod MAC collisions: {collision_vals}"
    else:
        # Reproducible proof of the trap: the 6-hex-octet rule catches none of
        # the 12 time-range FPs (they match the hex shape).
        assert coverage == 0, (
            f"6-hex-octet variant unexpectedly caught {coverage}/12 "
            "(trap no longer reproduces)"
        )
