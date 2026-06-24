//! Text chunker for the pipeline.
//!
//! Splits long text into overlapping chunks that fit GLiNER2's encoder
//! window (~512 tokens, ~1500 chars after schema overhead). Prefers
//! sentence and paragraph boundaries; falls back to word, then char.

#[derive(Debug, Clone, Copy)]
pub struct ChunkerConfig {
    pub max_chars: usize,
    pub overlap_chars: usize,
}

impl Default for ChunkerConfig {
    /// Conservative defaults for GLiNER2-large-v1 with a ~10-label schema:
    /// 1200-char chunks, 150-char overlap. Plenty of margin below the
    /// 512-token encoder limit even when labels grow.
    fn default() -> Self {
        Self {
            max_chars: 1200,
            overlap_chars: 150,
        }
    }
}

#[derive(Debug, Clone)]
pub struct Chunk<'a> {
    /// Slice of the source string for this chunk.
    pub text: &'a str,
    /// Byte offset in the source where this chunk starts.
    pub start: usize,
}

impl<'a> Chunk<'a> {
    pub fn end(&self) -> usize {
        self.start + self.text.len()
    }
}

#[derive(Debug, Clone)]
pub struct Chunker {
    cfg: ChunkerConfig,
}

impl Chunker {
    pub fn new(cfg: ChunkerConfig) -> Self {
        Self { cfg }
    }

    pub fn chunks<'a>(&self, text: &'a str) -> Vec<Chunk<'a>> {
        let mut chunks: Vec<Chunk<'a>> = Vec::new();
        if text.is_empty() {
            return chunks;
        }

        let n = text.len();
        let mut cursor: usize = 0;
        while cursor < n {
            let target_end = (cursor + self.cfg.max_chars).min(n);
            let chunk_end = if target_end == n {
                n
            } else {
                find_good_break(text, cursor, target_end)
            };

            // Safety: chunk_end must be on a char boundary AND > cursor.
            debug_assert!(text.is_char_boundary(chunk_end));
            debug_assert!(chunk_end > cursor);

            chunks.push(Chunk {
                text: &text[cursor..chunk_end],
                start: cursor,
            });

            if chunk_end >= n {
                break;
            }

            // Advance cursor: back off by overlap, but ensure forward progress.
            let next = chunk_end.saturating_sub(self.cfg.overlap_chars);
            let next = floor_char_boundary(text, next);
            cursor = if next <= cursor {
                chunk_end // overlap can't move backward past previous start
            } else {
                next
            };
        }

        chunks
    }
}

/// Find a "good" break point at or before `hard_end`. Searches the whole
/// chunk `[anchor..hard_end]` for the highest-quality boundary
/// (paragraph > sentence > line > word > char), but refuses breaks
/// closer to `anchor` than `soft_min` (= 30% of the chunk span) — that
/// keeps chunks from being too small if a paragraph break appears near
/// the start.
fn find_good_break(text: &str, anchor: usize, hard_end: usize) -> usize {
    let span = hard_end - anchor;
    let soft_min = anchor + (span * 3 / 10);
    let soft_min = floor_char_boundary(text, soft_min);
    let hard_end = floor_char_boundary(text, hard_end);
    let chunk = &text[anchor..hard_end];

    // 1. Paragraph break (\n\n)
    if let Some(rel) = chunk.rfind("\n\n") {
        let pos = anchor + rel + 2;
        if pos >= soft_min && pos <= hard_end && text.is_char_boundary(pos) {
            return pos;
        }
    }

    // 2. Sentence break: ".", "!", "?" followed by whitespace or end.
    for (rel_idx, c) in chunk.char_indices().rev() {
        if matches!(c, '.' | '!' | '?') {
            let after = anchor + rel_idx + c.len_utf8();
            if after < soft_min || after > hard_end {
                continue;
            }
            let next_is_ws = text
                .get(after..)
                .and_then(|s| s.chars().next())
                .map(char::is_whitespace)
                .unwrap_or(true);
            if next_is_ws && text.is_char_boundary(after) {
                return after;
            }
        }
    }

    // 3. Single newline
    if let Some(rel) = chunk.rfind('\n') {
        let pos = anchor + rel + 1;
        if pos >= soft_min && pos <= hard_end && text.is_char_boundary(pos) {
            return pos;
        }
    }

    // 4. Whitespace (space, tab)
    for (rel_idx, c) in chunk.char_indices().rev() {
        if c.is_whitespace() {
            let pos = anchor + rel_idx + c.len_utf8();
            if pos >= soft_min && pos <= hard_end && text.is_char_boundary(pos) {
                return pos;
            }
        }
    }

    // 5. Hard char boundary
    hard_end
}

fn floor_char_boundary(text: &str, mut i: usize) -> usize {
    i = i.min(text.len());
    while i > 0 && !text.is_char_boundary(i) {
        i -= 1;
    }
    i
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_input_no_chunks() {
        let c = Chunker::new(ChunkerConfig {
            max_chars: 100,
            overlap_chars: 10,
        });
        assert!(c.chunks("").is_empty());
    }

    #[test]
    fn short_input_one_chunk() {
        let c = Chunker::new(ChunkerConfig {
            max_chars: 100,
            overlap_chars: 10,
        });
        let chunks = c.chunks("Hello world.");
        assert_eq!(chunks.len(), 1);
        assert_eq!(chunks[0].text, "Hello world.");
        assert_eq!(chunks[0].start, 0);
    }

    #[test]
    fn long_input_multiple_chunks_with_overlap() {
        let c = Chunker::new(ChunkerConfig {
            max_chars: 50,
            overlap_chars: 10,
        });
        let text = "Sentence one. Sentence two. Sentence three. Sentence four. Sentence five. Sentence six.";
        let chunks = c.chunks(text);
        assert!(chunks.len() >= 2);
        // First chunk starts at 0
        assert_eq!(chunks[0].start, 0);
        // Subsequent chunks overlap with prior — start before prior end
        for w in chunks.windows(2) {
            let prior_end = w[0].end();
            assert!(w[1].start < prior_end, "chunks should overlap");
            assert!(w[1].start > w[0].start, "chunks should advance");
        }
        // Last chunk ends at text length
        assert_eq!(chunks.last().unwrap().end(), text.len());
    }

    #[test]
    fn coverage_is_complete() {
        let c = Chunker::new(ChunkerConfig {
            max_chars: 30,
            overlap_chars: 5,
        });
        let text = "The quick brown fox jumps over the lazy dog. Repeat one. Repeat two. Repeat three.";
        let chunks = c.chunks(text);
        // Concatenating chunks (with overlap) covers all source bytes.
        let mut covered = vec![false; text.len()];
        for ch in &chunks {
            for i in ch.start..ch.end() {
                covered[i] = true;
            }
        }
        assert!(covered.iter().all(|b| *b), "every byte must be covered");
    }

    #[test]
    fn prefers_sentence_boundary() {
        let c = Chunker::new(ChunkerConfig {
            max_chars: 40,
            overlap_chars: 5,
        });
        // Sentence ends near char 30; chunker should break there, not mid-word.
        let text = "Hello world here. Now next sentence keeps going further along.";
        let chunks = c.chunks(text);
        // First chunk should end on the period (after "Hello world here.")
        let first = chunks.first().unwrap();
        assert!(
            first.text.ends_with(". ") || first.text.ends_with("."),
            "first chunk should end at sentence boundary, got {:?}",
            first.text
        );
    }

    #[test]
    fn utf8_multibyte_safe() {
        let c = Chunker::new(ChunkerConfig {
            max_chars: 15,
            overlap_chars: 3,
        });
        // Each "é" is 2 bytes; mixed length chars near boundaries.
        let text = "Café crème, déjeuner à Zürich aujourd'hui.";
        let chunks = c.chunks(text);
        // No chunk should panic-slice into the middle of a multi-byte char.
        for ch in &chunks {
            assert!(text.is_char_boundary(ch.start));
            assert!(text.is_char_boundary(ch.end()));
        }
    }
}
