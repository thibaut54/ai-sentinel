"""Offline self-test — validates the benchmark harness with no network/endpoint.

Runs in a couple of seconds using the char-approx tokenizer and the offline stub
detector. It is the fast guarantee that the plumbing works (chunk coverage,
overlap dedup, value-level scoring, resume/cache, concept mapping, and a full
end-to-end unit) before committing to a multi-hour real run.

    python selftest.py
"""
from __future__ import annotations

import sys
import tempfile
from pathlib import Path

import chunkers
import scoring
from chunkers import chunk
from corpora import Doc, GoldDoc
from llmclient import StubClient, first_json_array, parse_entities
from merging import ConceptMap, Prediction, merge
from mistral_tokenizer import Tokenizer
from store import Store, call_key, unit_key

HERE = Path(__file__).resolve().parent
MAPPING = HERE.parent / "pii-dataset-eval" / "label_mapping.toml"

_passed = 0
_failed = 0


def check(name: str, cond: bool) -> None:
    global _passed, _failed
    if cond:
        _passed += 1
        print(f"  ok  {name}")
    else:
        _failed += 1
        print(f"  XX  {name}")


def test_tokenizer_fallback() -> None:
    print("[tokenizer]")
    tok = Tokenizer.char_approx(4)
    check("fallback is not real", not tok.is_real)
    check("count > 0", tok.count("hello world this is text") > 0)
    offs = tok.offsets("hello world")
    check("offsets cover text", offs[0][0] == 0 and offs[-1][1] == len("hello world"))


def test_chunk_coverage() -> None:
    print("[chunkers]")
    tok = Tokenizer.char_approx(4)
    text = ("Line one with an IBAN CH9300762011623852957.\n"
            "Line two. Sentence two here. And a third.\n\n"
            "Paragraph two starts here and runs on for a while.\n") * 6
    for boundary in chunkers.BOUNDARIES:
        chunks = chunk(text, tok, boundary, size_tokens=20, overlap=0.2)
        # Slices match the recorded spans.
        slices_ok = all(c.text == text[c.start:c.end] for c in chunks)
        # No gaps: sorted chunks tile [0, len].
        ordered = sorted(chunks, key=lambda c: c.start)
        covers = ordered[0].start == 0 and ordered[-1].end == len(text)
        contiguous = all(ordered[k + 1].start <= ordered[k].end for k in range(len(ordered) - 1))
        check(f"{boundary}: slices match spans", slices_ok)
        check(f"{boundary}: covers whole text, no gaps", covers and contiguous)
        check(f"{boundary}: produced multiple chunks", len(chunks) > 1)

    whole = chunk(text, tok, "whole", 999, 0.0)
    check("whole: single chunk", len(whole) == 1 and whole[0].end == len(text))


def test_chunk_bounded_without_seams() -> None:
    print("[chunkers / bounded overshoot]")
    tok = Tokenizer.char_approx(4)
    # A document with NO line/sentence/paragraph seams must NOT collapse into one
    # giant chunk: structural boundaries must stay near the target size.
    no_seams = "x" * 8000  # width at size=100 tokens, cpt=4 -> 400 chars
    width = 400
    for boundary in ("line", "sentence", "paragraph"):
        chunks = chunk(no_seams, tok, boundary, size_tokens=100, overlap=0.0)
        max_len = max(len(c.text) for c in chunks)
        check(f"{boundary}: bounded when no seam (n={len(chunks)}, max={max_len})",
              len(chunks) > 1 and max_len <= width * 1.3)


def test_overlap_dedup() -> None:
    print("[merge / cap dedup]")
    cmap = ConceptMap.load(MAPPING, "ministral")
    doc = "Contact a@b.com and again a@b.com plus the iban CH93 here."
    # Two overlapping chunks both return the duplicated email + the iban once.
    per_chunk = [[("a@b.com", "email"), ("CH93", "iban")],
                 [("a@b.com", "email"), ("a@b.com", "email")]]
    preds, stats = merge(per_chunk, doc, cmap)
    emails = [p for p in preds if p.value.lower() == "a@b.com"]
    # email -> IGNORE, so it must be dropped entirely (out of scope).
    check("out-of-scope email dropped", len(emails) == 0 and stats.dropped_ignore == 3)
    ibans = [p for p in preds if p.canonical == "IBAN"]
    check("iban kept once", len(ibans) == 1)

    # In-scope value duplicated across overlap: capped to its doc occurrence count.
    doc2 = "ip 1.2.3.4 then 1.2.3.4 again"  # occurs twice
    per2 = [[("1.2.3.4", "ip_address")], [("1.2.3.4", "ip_address")], [("1.2.3.4", "ip_address")]]
    preds2, _ = merge(per2, doc2, cmap)
    check("dup value capped at doc occurrences (2)", len(preds2) == 2)

    # Hallucinated value (not in doc): kept as a single FP, overlap-stable.
    per3 = [[("9.9.9.9", "ip_address")], [("9.9.9.9", "ip_address")]]
    preds3, stats3 = merge(per3, doc2, cmap)
    check("hallucination capped to 1", len(preds3) == 1 and stats3.hallucinated == 1)


def test_scoring() -> None:
    print("[scoring]")
    text = "iban CH9300762011623852957 and ssn 123-45-6789 end"
    gold = GoldDoc("d1", "t", text, [
        (5, 26, "IBAN"),          # CH9300762011623852957
        (35, 46, "NATIONAL_ID_NUMBER"),  # 123-45-6789
    ])
    preds = [Prediction("IBAN", "CH9300762011623852957"),
             Prediction("NATIONAL_ID_NUMBER", "123-45-6789")]
    sc = scoring.score_doc(gold, preds)
    check("perfect: f1 == 1", sc.strict.f1 == 1.0 and sc.strict.tp == 2)

    preds_bad = [Prediction("IBAN", "CH9300762011623852957"),  # tp
                 Prediction("IBAN", "WRONGVALUE")]              # fp; ssn missed -> fn
    sc2 = scoring.score_doc(gold, preds_bad)
    check("1 tp / 1 fp / 1 fn", sc2.strict.tp == 1 and sc2.strict.fp == 1 and sc2.strict.fn == 1)

    # Right value, wrong label: strict FN+FP but type-agnostic TP.
    sc3 = scoring.score_doc(gold, [Prediction("USERNAME", "CH9300762011623852957")])
    check("typing error: strict miss, type-agnostic hit",
          sc3.strict.tp == 0 and sc3.type_agnostic.tp == 1)


def test_concept_map() -> None:
    print("[concept map]")
    cmap = ConceptMap.load(MAPPING, "ministral")
    check("iban -> IBAN", cmap.canonical("iban") == "IBAN")
    check("email -> IGNORE (None)", cmap.canonical("email") is None)
    check("credit card label normalised", cmap.canonical("Credit-Card Number") == "CARD_NUMBER")
    cmap.canonical("totally_unknown_label_xyz")
    check("unknown label recorded", "totally_unknown_label_xyz" in cmap.unknown)


def test_json_parsing() -> None:
    print("[json parsing]")
    content = 'Sure! Here: [{"text":"a@b.com","label":"email"},{"value":"x","type":"iban"}] done'
    arr = first_json_array(content)
    ents = parse_entities(arr)
    check("extracts 2 entities w/ key variants", len(ents) == 2 and ents[1] == ("x", "iban"))
    check("no array -> None", first_json_array("no brackets here") is None)


def test_store_resume() -> None:
    print("[store / resume / cache]")
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp)
        s = Store(out)
        k = unit_key("A", "d1", "whole")
        check("not done initially", not s.is_done(k))
        s.add_result({"unit_key": k, "track": "A"})
        check("done after add", s.is_done(k))

        ck = call_key("m", 2048, True, "chunk text")
        check("cache miss", s.cache_get(ck) is None)
        from llmclient import CallResult
        s.cache_put(ck, CallResult(entities=[("v", "iban")], prompt_tokens=10, latency_ms=12.5))
        # Reload from disk to prove persistence.
        s2 = Store(out)
        got = s2.cache_get(ck)
        check("cache persisted + reloaded", got is not None and got.entities == [("v", "iban")])
        check("done-set persisted", s2.is_done(k))

        s2.add_failure({"unit_key": unit_key("A", "d2", "token-s1024-o0"), "reason": "timeout"})
        s3 = Store(out)
        check("retry set excludes completed, includes failed",
              unit_key("A", "d2", "token-s1024-o0") in s3.retry_units()
              and k not in s3.retry_units())


def test_end_to_end_stub() -> None:
    print("[end-to-end with stub]")
    from chunk_bench import Config, run_unit
    tok = Tokenizer.char_approx(4)
    cmap = ConceptMap.load(MAPPING, "stub")
    text = ("Client IBAN CH9300762011623852957, ip 10.0.0.1, tax CHE-116.281.710. " * 8)
    gold = GoldDoc("A-e2e", "megadoc", text, _find_spans(text))
    doc = Doc(id="A-e2e", text=text, target_tokens=64, actual_tokens=tok.count(text),
              capped=False, gold=gold)
    with tempfile.TemporaryDirectory() as tmp:
        store = Store(Path(tmp))
        row, fail = run_unit("A", doc, Config("token-s16-o20", "token", 16, 0.2),
                             StubClient(), tok, cmap, store, "stub", 2048, True)
        check("unit succeeded", fail is None and row is not None)
        check("has metrics", row["metrics"] is not None)
        check("found true positives", row["metrics"]["strict"]["tp"] > 0)
        check("recorded chunks", row["n_chunks"] >= 1)


def _find_spans(text: str):
    """Build gold spans for the IBAN/IP/tax values the stub will detect."""
    import re
    spans = []
    for pat, label in [(r"CH9300762011623852957", "IBAN"),
                       (r"10\.0\.0\.1", "IP_ADDRESS"),
                       (r"CHE-116\.281\.710", "TAX_NUMBER")]:
        for m in re.finditer(pat, text):
            spans.append((m.start(), m.end(), label))
    return spans


def main() -> int:
    for test in (test_tokenizer_fallback, test_chunk_coverage, test_chunk_bounded_without_seams,
                 test_overlap_dedup, test_scoring, test_concept_map, test_json_parsing,
                 test_store_resume, test_end_to_end_stub):
        test()
    print(f"\n{_passed} passed, {_failed} failed")
    return 1 if _failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
