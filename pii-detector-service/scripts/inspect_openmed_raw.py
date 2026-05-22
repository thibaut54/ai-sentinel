"""Inspect raw OpenMed pipeline output to understand the ACCOUNTNAME label.

The first version of this script proved that the model classifies person
names ("John Smith") under FIRSTNAME/LASTNAME, never ACCOUNTNAME. Hypothesis
to validate now: ACCOUNTNAME is reserved for **account identifiers** (numbers,
codes, aliases) and/or **entity-owned account titles** (non-personal),
distinct from BANKACCOUNT (raw bank numbers) and USERNAME (login handles).

This script feeds the raw pipeline various candidate phrasings and prints the
label the model picks, so we can rewrite the benchmark fixtures with formats
the model actually understands.

Bypasses OpenMedDetector entirely so the label_mapping / threshold filters do
not hide anything.
"""
from __future__ import annotations

import sys
from collections import Counter, defaultdict
from typing import Dict, List

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, OSError):
    pass

from openmed.torch.privacy_filter import PrivacyFilterTorchPipeline

MODEL_ID = "OpenMed/privacy-filter-multilingual"


# Candidate phrasings grouped by hypothesis category.
# We want to find which categories the model fires ACCOUNTNAME on.
CANDIDATES: Dict[str, List[str]] = {
    # Category A: numeric / alphanumeric account identifiers (NOT bank numbers,
    # i.e. shorter / formatted as a name-ish label)
    "account_id_alphanumeric": [
        "Account number ACC-2024-001.",
        "Internal account ID TREASURY-42.",
        "Account reference: OPS-MAIN-7788.",
        "Numero de compte interne: CPT-2024-AAA.",
        "Account code FX-001-CHF.",
        "Account ID: 100-MAIN-OPS.",
        "Compte interne reference R-2024-12-A.",
        "Account number: COMP-7788-EU.",
        "Account ID DELTA-OPS-001.",
        "Internal account label: GAMMA-7788.",
    ],

    # Category B: entity-named accounts (corporate/trust, NOT a person's full name)
    "entity_account_titles": [
        "Account: Wakefield Family Trust.",
        "Account titled \"Acme Corp Treasury\".",
        "Account name: Mueller GmbH Treasury.",
        "Beneficiary account: Olympia Holdings LLC.",
        "Account: Geneva Foundation for Climate.",
        "Trust account titled Wakefield Family Trust.",
        "Account beneficiary: Acme Corp Holdings.",
        "Account name: \"Helvetia Insurance Reserves\".",
        "Compte au nom de Fondation Helvetia.",
        "Account: ZEN Capital Partners GmbH.",
    ],

    # Category C: account handles / aliases (closer to username but with "account" context)
    "account_handles": [
        "Account alias: PRIMARY-OPS.",
        "Account handle @ops_team_2024.",
        "Internal account name: main_treasury.",
        "Account alias: backup_payroll_us.",
        "Account label: NORTHEAST_PAYOUTS.",
        "Compte alias: paie_principale_fr.",
        "Account handle backup-treasurer-eu.",
        "Account alias prod_payroll_v2.",
        "Account label EU_TRADING_DESK.",
        "Account alias: midnight_reserves.",
    ],

    # Category D: account holder NAME-as-string (free text after "account name:")
    # This is the format the original fixtures used (and that failed).
    "account_holder_personal_names": [
        "Account holder name: John A. Smith.",
        "The account is registered under Marie Dupont.",
        "Beneficiary on account: Aisha Karim.",
        "Account ownership: Khaled Ben Salem.",
        "Joint account name: Carlos and Elena Martinez.",
    ],

    # Category E: very explicit "account name" prefix with a non-person string
    "account_name_prefix_with_token": [
        "Account name: PRIMARY_TREASURY_USD.",
        "Account name: OPS-MAIN-CHF-001.",
        "Account name FX_HEDGE_2024_Q3.",
        "Account name: SAVINGS_RESERVE_EU.",
        "Compte nom: TREASURY_CH_2024.",
    ],
}


def run_category(pipeline, name: str, phrases: List[str]) -> Counter[str]:
    print("\n" + "=" * 84)
    print(f"CATEGORY: {name}  ({len(phrases)} phrases)")
    print("=" * 84)
    counter: Counter[str] = Counter()
    label_to_examples: Dict[str, List[str]] = defaultdict(list)

    for phrase in phrases:
        print(f"\n  {phrase!r}")
        raw = pipeline(phrase)
        if not raw:
            print("    (no detections)")
            continue
        for ent in raw:
            label = ent.get("entity_group") or ent.get("entity") or "?"
            score = float(ent.get("score", 0.0) or 0.0)
            word = ent.get("word", "")
            mark = "  <-- ACCOUNTNAME!" if label == "ACCOUNTNAME" else ""
            print(f"    - label={label:<18} score={score:.3f}  word={word!r}{mark}")
            counter[label] += 1
            if len(label_to_examples[label]) < 2:
                label_to_examples[label].append(f"{word!r} in {phrase[:50]!r}")

    print(f"\n  Label frequencies for {name}:")
    for label, count in counter.most_common():
        marker = " <-- ACCOUNTNAME" if label == "ACCOUNTNAME" else ""
        print(f"    {label:<22} {count:>4}{marker}")
    return counter


def main() -> None:
    print(f"Loading {MODEL_ID} ...")
    pipeline = PrivacyFilterTorchPipeline(MODEL_ID)
    print(f"Loaded. Pipeline device: {pipeline.device}")

    grand_total: Counter[str] = Counter()
    accountname_per_category: Dict[str, int] = {}
    for category, phrases in CANDIDATES.items():
        counter = run_category(pipeline, category, phrases)
        grand_total.update(counter)
        accountname_per_category[category] = counter.get("ACCOUNTNAME", 0)

    print("\n" + "=" * 84)
    print("SUMMARY — ACCOUNTNAME hits per category")
    print("=" * 84)
    for category, hits in accountname_per_category.items():
        total_phrases = len(CANDIDATES[category])
        print(f"  {category:<40} {hits:>3} ACCOUNTNAME hits across {total_phrases} phrases")

    print("\n" + "=" * 84)
    print("DIAGNOSIS")
    print("=" * 84)
    winners = [c for c, h in accountname_per_category.items() if h > 0]
    if not winners:
        print("  Model does NOT emit ACCOUNTNAME on ANY of the tested formats.")
        print("  Conclusion: the ACCOUNTNAME label is either dead in this multilingual")
        print("  finetune, or requires a very narrow surface form we have not guessed.")
        print("  Recommendation: disable ACCOUNTNAME and rely on BANKACCOUNT/IBAN/USERNAME.")
    else:
        print(f"  Model emits ACCOUNTNAME on these categories: {winners}")
        print("  Recommendation: rewrite the benchmark TP fixtures using formats from")
        print("  the winning categories above.")


if __name__ == "__main__":
    main()
