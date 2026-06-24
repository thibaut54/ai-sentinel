//! Integration test: run the real baseline taxonomy's regex layer
//! against a hand-crafted Swiss text containing known-valid examples
//! of every Tier A IPI.

use std::collections::HashSet;

use ai_sentinel_pii_detector::{Finding, regex_layer::RegexLayer, taxonomy::Taxonomy};

const SAMPLE: &str = "\
Bonjour, mon AVS est 756.1234.5678.97 et mon IBAN est CH93 0076 2011 6238 5295 7. \
Numéro de carte de crédit: 4111 1111 1111 1111. \
Contact: alice.dupont@example.ch, téléphone +41 79 555 12 34. \
Serveur: 192.168.1.42 (MAC 00:1A:2B:3C:4D:5E). \
api_key = aZ7xQ9pL3mK2nB8vF4cR6tY1wE5sD0";

#[test]
fn baseline_regex_layer_finds_all_tier_a_patterns() {
    let tax = Taxonomy::load("config/nlpd-ipi.toml").expect("baseline must load");
    let layer = RegexLayer::from_taxonomy(&tax).expect("layer must build");
    assert!(layer.ipi_count() >= 7, "expected at least 7 regex IPIs in baseline");

    let findings = layer.detect(SAMPLE);

    let ids: HashSet<&str> = findings.iter().map(|f| f.ipi_id.as_str()).collect();
    for expected in [
        "AVS_NUMBER",
        "IBAN_CODE",
        "CREDIT_CARD_NUMBER",
        "EMAIL_ADDRESS",
        "SWISS_PHONE_NUMBER",
        "IP_ADDRESS",
        "MAC_ADDRESS",
        "API_KEY_OR_SECRET",
    ] {
        assert!(
            ids.contains(expected),
            "expected to find {expected}; got ids = {ids:?}"
        );
    }

    // AVS must pass swiss_avs validator — the bad check digit case is
    // covered in regex_layer unit tests; here we just confirm the good
    // one comes through with correct text.
    let avs: &Finding = findings
        .iter()
        .find(|f| f.ipi_id == "AVS_NUMBER")
        .expect("AVS not found");
    assert_eq!(avs.matched_text, "756.1234.5678.97");

    // IBAN must pass iban_checksum.
    let iban: &Finding = findings
        .iter()
        .find(|f| f.ipi_id == "IBAN_CODE")
        .expect("IBAN not found");
    assert!(iban.matched_text.starts_with("CH93"));

    // Credit card must pass luhn.
    let cc: &Finding = findings
        .iter()
        .find(|f| f.ipi_id == "CREDIT_CARD_NUMBER")
        .expect("credit card not found");
    assert!(cc.matched_text.contains("4111"));
}

#[test]
fn baseline_invalid_iban_is_rejected_by_checksum() {
    let tax = Taxonomy::load("config/nlpd-ipi.toml").expect("baseline must load");
    let layer = RegexLayer::from_taxonomy(&tax).expect("layer must build");

    // CH9300762011623852958 — last digit flipped, fails mod-97.
    let findings = layer.detect("here's a bogus IBAN: CH9300762011623852958 do not bill it");
    let iban_findings: Vec<&Finding> = findings
        .iter()
        .filter(|f| f.ipi_id == "IBAN_CODE")
        .collect();
    assert!(
        iban_findings.is_empty(),
        "checksum-failing IBAN must be dropped; got {iban_findings:?}"
    );
}

#[test]
fn baseline_invalid_credit_card_is_rejected_by_luhn() {
    let tax = Taxonomy::load("config/nlpd-ipi.toml").expect("baseline must load");
    let layer = RegexLayer::from_taxonomy(&tax).expect("layer must build");

    // 4111 1111 1111 1112 — last digit off by one, fails Luhn.
    let findings = layer.detect("bad CC 4111 1111 1111 1112 here");
    let cc_findings: Vec<&Finding> = findings
        .iter()
        .filter(|f| f.ipi_id == "CREDIT_CARD_NUMBER")
        .collect();
    assert!(
        cc_findings.is_empty(),
        "Luhn-failing card must be dropped; got {cc_findings:?}"
    );
}
