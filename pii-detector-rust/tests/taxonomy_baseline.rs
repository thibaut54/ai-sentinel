//! Integration test against the actual baseline TOML shipped with the crate.
//! Sanity-checks the schema by loading the real file rather than fixtures.

use ai_sentinel_pii_detector::taxonomy::{Layer, Severity, Taxonomy};

#[test]
fn baseline_loads_and_validates() {
    let tax = Taxonomy::load("config/nlpd-ipi.toml")
        .expect("config/nlpd-ipi.toml must load");

    assert!(!tax.ipis.is_empty(), "baseline has no IPIs");
    assert!(!tax.categories.is_empty(), "baseline has no categories");

    let counts = tax.layer_counts();
    assert!(counts.get(&Layer::Regex).copied().unwrap_or(0) > 0, "no regex IPIs");
    assert!(counts.get(&Layer::Ner).copied().unwrap_or(0) > 0, "no NER IPIs");
    // Classification layer is intentionally empty in this baseline
    // (NRP removed). Re-assert classification count when classification IPIs return.

    // AVS is the bellwether — if it's not there, something's very wrong.
    let avs = tax.by_id("AVS_NUMBER").expect("AVS_NUMBER missing");
    assert_eq!(avs.severity, Severity::High);
    assert_eq!(avs.country.as_deref(), Some("CH"));
    assert_eq!(avs.detection.layer(), Layer::Regex);
}

#[test]
fn baseline_plus_example_override_merges_cleanly() {
    let mut tax = Taxonomy::load("config/nlpd-ipi.toml")
        .expect("baseline must load");
    tax.apply_override("config/overrides/example-tenant.toml")
        .expect("example override must apply");

    // CREDIT_CARD_NUMBER priority bumped to 100.
    let cc = tax.by_id("CREDIT_CARD_NUMBER").expect("CC missing");
    assert_eq!(cc.priority, 100);

    // SOCIALNUM disabled by the override.
    let socialnum = tax.by_id("SOCIALNUM").expect("SOCIALNUM missing");
    assert!(!socialnum.enabled);

    // Custom IPI appended.
    let custom = tax
        .by_id("INTERNAL_PROJECT_CODENAME")
        .expect("custom IPI not added");
    assert_eq!(custom.detection.layer(), Layer::Regex);
    assert_eq!(custom.priority, 110);
}
