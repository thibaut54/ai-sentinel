use ndarray::{Array2, ArrayView2, s};

use crate::error::Result;
use crate::gliner2::input::{NerInput, build_ner_input};
use crate::gliner2::runtime::Runtime;
use crate::gliner2::tokens::{
    ONNX_ATTENTION_MASK, ONNX_HIDDEN_STATES, ONNX_INPUT_IDS, ONNX_LABEL_EMBEDDINGS,
    ONNX_SPAN_END_IDX, ONNX_SPAN_START_IDX,
};
use crate::gliner2::types::Entity;

impl Runtime {
    /// Extract named entities. `labels` are the candidate entity types
    /// (zero-shot — any string). `threshold` is the sigmoid score cutoff.
    pub fn extract_entities(
        &self,
        text: &str,
        labels: &[&str],
        descriptions: Option<&[&str]>,
        threshold: f32,
    ) -> Result<Vec<Entity>> {
        if text.trim().is_empty() {
            return Err(crate::error::Error::EmptyInput("text"));
        }
        if labels.is_empty() {
            return Err(crate::error::Error::EmptyInput("labels"));
        }

        let input = build_ner_input(&self.tokenizer, &self.config, text, labels, descriptions)?;
        if input.word_offsets.is_empty() {
            return Ok(Vec::new());
        }

        let hidden = self.encode(&input)?;

        // Gather label embeddings at [E] positions → (num_labels, hidden).
        let hidden_size = hidden.ncols();
        let mut label_emb: Array2<f32> = Array2::zeros((labels.len(), hidden_size));
        for (row, &pos) in input.e_positions.iter().enumerate() {
            label_emb
                .slice_mut(s![row, ..])
                .assign(&hidden.slice(s![pos, ..]));
        }

        // Slice hidden_states[text_start_idx ..] → text-only hidden states.
        let text_hidden = hidden.slice(s![input.text_start_idx.., ..]).to_owned();
        let text_hidden_3d = text_hidden
            .clone()
            .into_shape_with_order((1, text_hidden.nrows(), text_hidden.ncols()))
            .expect("text_hidden reshape");

        // Generate word-level spans and translate to token indices.
        let num_words = input.word_offsets.len();
        let (word_starts, word_ends) = generate_word_spans(num_words, self.config.max_width);
        let token_starts: Vec<i64> = word_starts
            .iter()
            .map(|&w| input.first_token_positions[w] as i64)
            .collect();
        let token_ends: Vec<i64> = word_ends
            .iter()
            .map(|&w| input.first_token_positions[w] as i64)
            .collect();

        let n_spans = token_starts.len();
        let span_start_arr: Array2<i64> =
            Array2::from_shape_vec((1, n_spans), token_starts).expect("span_start shape");
        let span_end_arr: Array2<i64> =
            Array2::from_shape_vec((1, n_spans), token_ends).expect("span_end shape");

        let hs_t = ort::value::Value::from_array(text_hidden_3d)?;
        let ss_t = ort::value::Value::from_array(span_start_arr)?;
        let se_t = ort::value::Value::from_array(span_end_arr)?;
        let (sr_shape, sr_data) = {
            let mut g = self.sessions.span_rep.lock().expect("span_rep mutex");
            let outputs = g.run(ort::inputs![
                ONNX_HIDDEN_STATES => hs_t,
                ONNX_SPAN_START_IDX => ss_t,
                ONNX_SPAN_END_IDX => se_t,
            ])?;
            let (shape, data) = outputs[0].try_extract_tensor::<f32>()?;
            (
                shape.iter().map(|&d| d as usize).collect::<Vec<usize>>(),
                data.to_vec(),
            )
        };
        debug_assert_eq!(sr_shape.len(), 3, "span_rep output must be 3-D");
        let span_rep = ArrayView2::from_shape((sr_shape[1], sr_shape[2]), &sr_data)
            .expect("span_rep reshape")
            .to_owned();

        // count_embed transforms label embeddings into scoring space.
        let label_emb_t = ort::value::Value::from_array(label_emb)?;
        let (ce_shape, ce_data) = {
            let mut g = self.sessions.count_embed.lock().expect("count_embed mutex");
            let outputs = g.run(ort::inputs![ONNX_LABEL_EMBEDDINGS => label_emb_t])?;
            let (shape, data) = outputs[0].try_extract_tensor::<f32>()?;
            (
                shape.iter().map(|&d| d as usize).collect::<Vec<usize>>(),
                data.to_vec(),
            )
        };
        let transformed = ArrayView2::from_shape((ce_shape[0], ce_shape[1]), &ce_data)
            .expect("count_embed reshape")
            .to_owned();

        // scores[span, label] = sigmoid(span_rep[span] · transformed[label])
        let scores: Array2<f32> = sigmoid_inplace(span_rep.dot(&transformed.t()));

        let entities = collect_entities(
            &scores,
            &word_starts,
            &word_ends,
            &input,
            labels,
            text,
            threshold,
        );

        Ok(deduplicate(entities))
    }

    fn encode(&self, input: &NerInput) -> Result<Array2<f32>> {
        let seq_len = input.input_ids.len();
        let input_ids: Array2<i64> = Array2::from_shape_vec(
            (1, seq_len),
            input.input_ids.iter().map(|&id| id as i64).collect(),
        )
        .expect("input_ids shape");
        let attention_mask: Array2<i64> = Array2::ones((1, seq_len));

        let input_ids_t = ort::value::Value::from_array(input_ids)?;
        let mask_t = ort::value::Value::from_array(attention_mask)?;

        let (shape, data) = {
            let mut g = self.sessions.encoder.lock().expect("encoder mutex");
            let outputs = g.run(ort::inputs![
                ONNX_INPUT_IDS => input_ids_t,
                ONNX_ATTENTION_MASK => mask_t,
            ])?;
            let (shape, data) = outputs[0].try_extract_tensor::<f32>()?;
            (
                shape.iter().map(|&d| d as usize).collect::<Vec<usize>>(),
                data.to_vec(),
            )
        };
        debug_assert_eq!(shape.len(), 3, "encoder output must be 3-D");
        debug_assert_eq!(shape[0], 1);

        let mut hidden = Array2::<f32>::zeros((shape[1], shape[2]));
        for i in 0..shape[1] {
            for j in 0..shape[2] {
                hidden[[i, j]] = data[i * shape[2] + j];
            }
        }
        Ok(hidden)
    }
}

fn generate_word_spans(num_words: usize, max_width: usize) -> (Vec<usize>, Vec<usize>) {
    let mut starts = Vec::new();
    let mut ends = Vec::new();
    for i in 0..num_words {
        let w_max = max_width.min(num_words - i);
        for j in 0..w_max {
            starts.push(i);
            ends.push(i + j);
        }
    }
    (starts, ends)
}

fn collect_entities(
    scores: &Array2<f32>,
    word_starts: &[usize],
    word_ends: &[usize],
    input: &NerInput,
    labels: &[&str],
    text: &str,
    threshold: f32,
) -> Vec<Entity> {
    let mut out: Vec<Entity> = Vec::new();
    for (span_idx, (&s_word, &e_word)) in word_starts.iter().zip(word_ends.iter()).enumerate() {
        for (label_idx, &label) in labels.iter().enumerate() {
            let score = scores[[span_idx, label_idx]];
            if score >= threshold {
                let char_start = input.word_offsets[s_word].0;
                let char_end = input.word_offsets[e_word].1;
                if char_end <= text.len() {
                    out.push(Entity {
                        text: text[char_start..char_end].to_string(),
                        label: label.to_string(),
                        start: char_start,
                        end: char_end,
                        score,
                    });
                }
            }
        }
    }
    out
}

fn deduplicate(mut entities: Vec<Entity>) -> Vec<Entity> {
    if entities.is_empty() {
        return entities;
    }
    entities.sort_by(|a, b| {
        b.score
            .partial_cmp(&a.score)
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    let mut kept: Vec<Entity> = Vec::with_capacity(entities.len());
    for ent in entities {
        let overlaps = kept
            .iter()
            .any(|k| k.label == ent.label && ent.start < k.end && ent.end > k.start);
        if !overlaps {
            kept.push(ent);
        }
    }
    kept
}

fn sigmoid_inplace(mut a: Array2<f32>) -> Array2<f32> {
    a.mapv_inplace(|x| {
        if x >= 0.0 {
            1.0 / (1.0 + (-x).exp())
        } else {
            let e = x.exp();
            e / (1.0 + e)
        }
    });
    a
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn span_generation_respects_max_width() {
        let (starts, ends) = generate_word_spans(5, 3);
        let pairs: Vec<(usize, usize)> = starts.into_iter().zip(ends).collect();
        assert_eq!(
            pairs,
            vec![
                (0, 0), (0, 1), (0, 2),
                (1, 1), (1, 2), (1, 3),
                (2, 2), (2, 3), (2, 4),
                (3, 3), (3, 4),
                (4, 4),
            ]
        );
    }

    #[test]
    fn dedup_keeps_highest_per_label() {
        let entities = vec![
            Entity { text: "foo".into(), label: "A".into(), start: 0, end: 3, score: 0.7 },
            Entity { text: "foo".into(), label: "A".into(), start: 0, end: 3, score: 0.9 },
            Entity { text: "foo".into(), label: "B".into(), start: 0, end: 3, score: 0.8 },
        ];
        let kept = deduplicate(entities);
        assert_eq!(kept.len(), 2);
        assert_eq!(kept[0].score, 0.9);
        assert_eq!(kept[0].label, "A");
        assert_eq!(kept[1].label, "B");
    }
}
