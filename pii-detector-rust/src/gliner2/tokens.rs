use std::sync::LazyLock;

use regex::Regex;

pub const TOKEN_P: &str = "[P]";
pub const TOKEN_L: &str = "[L]";
pub const TOKEN_E: &str = "[E]";
pub const TOKEN_DESCRIPTION: &str = "[DESCRIPTION]";
pub const TOKEN_SEP_TEXT: &str = "[SEP_TEXT]";

pub const SCHEMA_OPEN: &str = "(";
pub const SCHEMA_CLOSE: &str = ")";

pub const NER_TASK_NAME: &str = "entities";
pub const CLASSIFICATION_TASK_NAME: &str = "category";

pub const ONNX_INPUT_IDS: &str = "input_ids";
pub const ONNX_ATTENTION_MASK: &str = "attention_mask";
pub const ONNX_HIDDEN_STATE: &str = "hidden_state";
pub const ONNX_HIDDEN_STATES: &str = "hidden_states";
pub const ONNX_SPAN_START_IDX: &str = "span_start_idx";
pub const ONNX_SPAN_END_IDX: &str = "span_end_idx";
pub const ONNX_LABEL_EMBEDDINGS: &str = "label_embeddings";

pub static WORD_PATTERN: LazyLock<Regex> = LazyLock::new(|| {
    Regex::new(
        r"(?ix)
        (?: https?://\S+ | www\.\S+ )
        | [a-z0-9._%+\-]+ @ [a-z0-9.\-]+ \. [a-z]{2,}
        | @ [a-z0-9_]+
        | \w+ (?: [\-_]\w+ )*
        | \S
        ",
    )
    .expect("WORD_PATTERN must compile")
});

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn word_pattern_matches_words() {
        let matches: Vec<&str> = WORD_PATTERN
            .find_iter("John works at Google.")
            .map(|m| m.as_str())
            .collect();
        assert_eq!(matches, vec!["John", "works", "at", "Google", "."]);
    }

    #[test]
    fn word_pattern_matches_email() {
        let matches: Vec<&str> = WORD_PATTERN
            .find_iter("contact alice.smith@example.com today")
            .map(|m| m.as_str())
            .collect();
        assert_eq!(
            matches,
            vec!["contact", "alice.smith@example.com", "today"]
        );
    }

    #[test]
    fn word_pattern_matches_url() {
        let matches: Vec<&str> = WORD_PATTERN
            .find_iter("see https://example.com/path for details")
            .map(|m| m.as_str())
            .collect();
        assert_eq!(
            matches,
            vec!["see", "https://example.com/path", "for", "details"]
        );
    }
}
