use crate::gliner2::tokens::WORD_PATTERN;

/// Byte offsets `(start, end)` of each word in the lowercased input text.
///
/// Mirrors the Python reference's `WORD_PATTERN.finditer(text.lower())` walk.
/// The caller is expected to feed an already-lowercased string; offsets are
/// byte indices into that lowercased string. For ASCII and the overwhelming
/// majority of UTF-8 text (including French/Italian/German diacritics),
/// lowercasing preserves byte length, so these offsets remain valid against
/// the original (mixed-case) text as well.
pub fn split_words(lowercased: &str) -> Vec<(usize, usize)> {
    WORD_PATTERN
        .find_iter(lowercased)
        .map(|m| (m.start(), m.end()))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn splits_basic_sentence() {
        let text = "john works at google".to_string();
        let words = split_words(&text);
        let strs: Vec<&str> = words.iter().map(|&(s, e)| &text[s..e]).collect();
        assert_eq!(strs, vec!["john", "works", "at", "google"]);
    }

    #[test]
    fn keeps_email_as_single_word() {
        let text = "contact alice.smith@example.com today".to_string();
        let words = split_words(&text);
        let strs: Vec<&str> = words.iter().map(|&(s, e)| &text[s..e]).collect();
        assert_eq!(strs, vec!["contact", "alice.smith@example.com", "today"]);
    }

    #[test]
    fn punctuation_is_single_token() {
        let text = "hello, world.".to_string();
        let words = split_words(&text);
        let strs: Vec<&str> = words.iter().map(|&(s, e)| &text[s..e]).collect();
        assert_eq!(strs, vec!["hello", ",", "world", "."]);
    }
}
