use tokenizers::Tokenizer;

use crate::error::{Error, Result};
use crate::gliner2::config::GLiNER2Config;
use crate::gliner2::tokens::{
    CLASSIFICATION_TASK_NAME, NER_TASK_NAME, SCHEMA_CLOSE, SCHEMA_OPEN, TOKEN_DESCRIPTION,
    TOKEN_E, TOKEN_L, TOKEN_P, TOKEN_SEP_TEXT,
};
use crate::gliner2::word_split::split_words;

/// Encoded input for a classification call.
pub struct ClassificationInput {
    pub input_ids: Vec<u32>,
    pub label_positions: Vec<usize>,
}

/// Encoded input for an NER call. Includes the bookkeeping needed to map
/// model outputs back to character offsets in the original text.
pub struct NerInput {
    pub input_ids: Vec<u32>,
    /// Token positions of each `[E]` marker (one per label).
    pub e_positions: Vec<usize>,
    /// Index in `input_ids` where the text portion begins.
    pub text_start_idx: usize,
    /// `(char_start, char_end)` in the lowercased text for each word.
    pub word_offsets: Vec<(usize, usize)>,
    /// Token index (within the text portion) where each word starts.
    pub first_token_positions: Vec<usize>,
}

/// Internal: build the schema prefix `( [P] <task> ( [L|E] l1 [L|E] l2 ... ) ) [SEP_TEXT]`.
struct SchemaPrefix {
    tokens: Vec<u32>,
    label_positions: Vec<usize>,
}

fn build_schema_prefix(
    tokenizer: &Tokenizer,
    config: &GLiNER2Config,
    task_name: &str,
    labels: &[&str],
    descriptions: Option<&[&str]>,
    label_token: &str,
) -> Result<SchemaPrefix> {
    let p_id = config.special_token_id(TOKEN_P)?;
    let label_id = config.special_token_id(label_token)?;
    let sep_text_id = config.special_token_id(TOKEN_SEP_TEXT)?;
    let description_id = config.special_token_id(TOKEN_DESCRIPTION)?;

    if let Some(d) = descriptions {
        if d.len() != labels.len() {
            return Err(Error::Tokenizer(format!(
                "build_schema_prefix: descriptions.len() ({}) != labels.len() ({})",
                d.len(),
                labels.len()
            )));
        }
    }

    let mut tokens: Vec<u32> = Vec::with_capacity(32 + labels.len() * 16);
    tokens.extend(encode(tokenizer, SCHEMA_OPEN)?);
    tokens.push(p_id);
    tokens.extend(encode(tokenizer, task_name)?);
    tokens.extend(encode(tokenizer, SCHEMA_OPEN)?);

    let mut label_positions: Vec<usize> = Vec::with_capacity(labels.len());
    for (i, label) in labels.iter().enumerate() {
        label_positions.push(tokens.len());
        tokens.push(label_id);
        tokens.extend(encode(tokenizer, label)?);
        if let Some(d) = descriptions {
            let desc = d[i];
            if !desc.is_empty() {
                tokens.push(description_id);
                tokens.extend(encode(tokenizer, desc)?);
            }
        }
    }

    tokens.extend(encode(tokenizer, SCHEMA_CLOSE)?);
    tokens.extend(encode(tokenizer, SCHEMA_CLOSE)?);
    tokens.push(sep_text_id);

    Ok(SchemaPrefix {
        tokens,
        label_positions,
    })
}

pub fn build_classification_input(
    tokenizer: &Tokenizer,
    config: &GLiNER2Config,
    text: &str,
    labels: &[&str],
    descriptions: Option<&[&str]>,
) -> Result<ClassificationInput> {
    let SchemaPrefix {
        mut tokens,
        label_positions,
    } = build_schema_prefix(
        tokenizer,
        config,
        CLASSIFICATION_TASK_NAME,
        labels,
        descriptions,
        TOKEN_L,
    )?;

    let lower = text.to_lowercase();
    for (start, end) in split_words(&lower) {
        tokens.extend(encode(tokenizer, &lower[start..end])?);
    }

    Ok(ClassificationInput {
        input_ids: tokens,
        label_positions,
    })
}

pub fn build_ner_input(
    tokenizer: &Tokenizer,
    config: &GLiNER2Config,
    text: &str,
    labels: &[&str],
    descriptions: Option<&[&str]>,
) -> Result<NerInput> {
    let SchemaPrefix {
        mut tokens,
        label_positions: e_positions,
    } = build_schema_prefix(
        tokenizer,
        config,
        NER_TASK_NAME,
        labels,
        descriptions,
        TOKEN_E,
    )?;

    let text_start_idx = tokens.len();

    let lower = text.to_lowercase();
    let word_offsets = split_words(&lower);
    let mut first_token_positions: Vec<usize> = Vec::with_capacity(word_offsets.len());
    let mut token_idx_in_text: usize = 0;

    for &(start, end) in &word_offsets {
        first_token_positions.push(token_idx_in_text);
        let word_tokens = encode(tokenizer, &lower[start..end])?;
        token_idx_in_text += word_tokens.len();
        tokens.extend(word_tokens);
    }

    Ok(NerInput {
        input_ids: tokens,
        e_positions,
        text_start_idx,
        word_offsets,
        first_token_positions,
    })
}

fn encode(tokenizer: &Tokenizer, text: &str) -> Result<Vec<u32>> {
    let enc = tokenizer
        .encode(text, false)
        .map_err(|e| Error::Tokenizer(e.to_string()))?;
    Ok(enc.get_ids().to_vec())
}
