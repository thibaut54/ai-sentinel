//! Span-overlap resolution for [`Finding`]s.
//!
//! When two **span findings** (regex / NER) overlap and have different
//! priorities, the lower-priority finding is dropped. Same-priority
//! overlaps are kept ("Apple Store" can be both organization and location).
//!
//! **Classification findings are exempt.** They describe the whole input
//! ("this section discusses health"), not a specific span — they coexist
//! with span findings and are never resolved against them. The resolver
//! passes them through untouched.
//!
//! Use after detection layers run, before reporting.

use std::cmp::Ordering;

use crate::findings::{Finding, SourceLayer};

/// Drop findings whose span overlaps a **strictly higher-priority** finding.
///
/// Processing order: priority desc, score desc, span length desc, start asc.
/// The longer-span tiebreak means that when two equal-priority findings
/// nest, the outer one is processed first (and thus kept — same-priority
/// overlaps are not dropped here).
///
/// The returned vector is re-sorted by `start` ascending for stable output.
pub fn resolve_overlaps(findings: Vec<Finding>) -> Vec<Finding> {
    // Split out classification findings — they bypass span resolution entirely.
    let (mut spans, classifications): (Vec<Finding>, Vec<Finding>) = findings
        .into_iter()
        .partition(|f| f.source_layer != SourceLayer::Classification);

    // Priority desc, score desc, longer span first, earlier start first.
    spans.sort_by(|a, b| {
        b.priority
            .cmp(&a.priority)
            .then_with(|| {
                b.score
                    .partial_cmp(&a.score)
                    .unwrap_or(Ordering::Equal)
            })
            .then_with(|| (b.end - b.start).cmp(&(a.end - a.start)))
            .then_with(|| a.start.cmp(&b.start))
    });

    let mut kept: Vec<Finding> = Vec::with_capacity(spans.len() + classifications.len());
    'next: for f in spans {
        for k in &kept {
            if k.priority > f.priority && spans_overlap(k.start, k.end, f.start, f.end) {
                continue 'next;
            }
        }
        kept.push(f);
    }

    // Re-attach classification findings untouched.
    kept.extend(classifications);
    kept.sort_by_key(|f| f.start);
    kept
}

#[inline]
fn spans_overlap(a_start: usize, a_end: usize, b_start: usize, b_end: usize) -> bool {
    a_start < b_end && b_start < a_end
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::findings::SourceLayer;
    use crate::taxonomy::Severity;

    fn f(id: &str, start: usize, end: usize, priority: i32) -> Finding {
        Finding {
            ipi_id: id.to_string(),
            matched_text: "x".repeat(end - start),
            start,
            end,
            score: 0.9,
            priority,
            severity: Severity::Low,
            source_layer: SourceLayer::Regex,
        }
    }

    #[test]
    fn higher_priority_overlap_wins() {
        // AVS 0..16 (pri 100) covering POSTAL 4..8 (pri 40) → only AVS kept.
        let findings = vec![f("AVS", 0, 16, 100), f("POSTAL", 4, 8, 40)];
        let kept = resolve_overlaps(findings);
        assert_eq!(kept.len(), 1);
        assert_eq!(kept[0].ipi_id, "AVS");
    }

    #[test]
    fn same_priority_overlap_both_kept() {
        // ORG and LOCATION on the same span, both priority 50 → keep both.
        let findings = vec![f("ORG", 0, 11, 50), f("LOCATION", 0, 11, 50)];
        let kept = resolve_overlaps(findings);
        assert_eq!(kept.len(), 2);
    }

    #[test]
    fn non_overlapping_all_kept() {
        let findings = vec![
            f("AVS", 0, 16, 100),
            f("POSTAL", 20, 24, 40),
            f("IP", 30, 41, 60),
        ];
        let kept = resolve_overlaps(findings);
        assert_eq!(kept.len(), 3);
    }

    #[test]
    fn three_level_chain() {
        // Outer (pri 100) covers middle (pri 50) covers inner (pri 40).
        // Only outer should survive.
        let findings = vec![
            f("OUTER", 0, 30, 100),
            f("MIDDLE", 5, 15, 50),
            f("INNER", 10, 12, 40),
        ];
        let kept = resolve_overlaps(findings);
        assert_eq!(kept.len(), 1);
        assert_eq!(kept[0].ipi_id, "OUTER");
    }

    #[test]
    fn lower_priority_outside_higher_is_kept() {
        // POSTAL adjacent to but not inside AVS — keep both.
        let findings = vec![f("AVS", 0, 16, 100), f("POSTAL", 20, 24, 40)];
        let kept = resolve_overlaps(findings);
        assert_eq!(kept.len(), 2);
    }

    #[test]
    fn higher_priority_inside_lower_priority_still_wins() {
        // Inverted nesting: a higher-priority but narrower span inside a
        // lower-priority broader span. The lower-priority is dropped
        // because it overlaps a higher-priority finding (the narrow one).
        let findings = vec![f("BROAD_LOW", 0, 30, 40), f("NARROW_HIGH", 10, 12, 100)];
        let kept = resolve_overlaps(findings);
        assert_eq!(kept.len(), 1);
        assert_eq!(kept[0].ipi_id, "NARROW_HIGH");
    }

    #[test]
    fn empty_input_returns_empty() {
        assert!(resolve_overlaps(Vec::new()).is_empty());
    }

    fn cls(id: &str, start: usize, end: usize, priority: i32) -> Finding {
        Finding {
            ipi_id: id.to_string(),
            matched_text: String::new(),
            start,
            end,
            score: 0.9,
            priority,
            severity: Severity::High,
            source_layer: SourceLayer::Classification,
        }
    }

    #[test]
    fn classification_findings_pass_through_regardless_of_overlap() {
        // A high-priority span and a classification finding that "covers" it.
        // Both must survive — they describe different scopes.
        let findings = vec![
            f("AVS", 100, 116, 100),
            cls("HEALTH_DIAGNOSIS", 0, 500, 80),
        ];
        let kept = resolve_overlaps(findings);
        assert_eq!(kept.len(), 2);
        let ids: Vec<&str> = kept.iter().map(|k| k.ipi_id.as_str()).collect();
        assert!(ids.contains(&"AVS"));
        assert!(ids.contains(&"HEALTH_DIAGNOSIS"));
    }

    #[test]
    fn classification_does_not_suppress_other_findings() {
        // Even with very high priority, a classification finding must NOT
        // suppress overlapping span findings.
        let findings = vec![
            f("LOW_SPAN", 10, 20, 10),
            cls("CRIMINAL_RECORD", 0, 500, 999),
        ];
        let kept = resolve_overlaps(findings);
        assert_eq!(kept.len(), 2);
    }

    #[test]
    fn output_sorted_by_start() {
        let findings = vec![
            f("C", 30, 35, 50),
            f("A", 0, 5, 50),
            f("B", 15, 20, 50),
        ];
        let kept = resolve_overlaps(findings);
        assert_eq!(kept[0].ipi_id, "A");
        assert_eq!(kept[1].ipi_id, "B");
        assert_eq!(kept[2].ipi_id, "C");
    }
}
