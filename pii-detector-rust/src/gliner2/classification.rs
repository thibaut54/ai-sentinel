use ndarray::{Array2, ArrayView2, s};

use crate::error::Result;
use crate::gliner2::input::build_classification_input;
use crate::gliner2::runtime::Runtime;
use crate::gliner2::tokens::{ONNX_ATTENTION_MASK, ONNX_HIDDEN_STATE, ONNX_INPUT_IDS};
use crate::gliner2::types::Classification;

#[derive(Debug, Clone, Copy)]
pub struct ClassifyOptions {
    pub threshold: f32,
    pub multi_label: bool,
}

impl Default for ClassifyOptions {
    fn default() -> Self {
        Self {
            threshold: 0.5,
            multi_label: false,
        }
    }
}

impl Runtime {
    /// Classify `text` against the given candidate labels.
    ///
    /// In single-label mode (default), returns the highest-scoring label via
    /// softmax. In multi-label mode, returns all labels with sigmoid score
    /// above `threshold`.
    pub fn classify(
        &self,
        text: &str,
        labels: &[&str],
        descriptions: Option<&[&str]>,
        opts: ClassifyOptions,
    ) -> Result<Vec<Classification>> {
        if text.trim().is_empty() {
            return Err(crate::error::Error::EmptyInput("text"));
        }
        if labels.is_empty() {
            return Err(crate::error::Error::EmptyInput("labels"));
        }

        let input = build_classification_input(
            &self.tokenizer,
            &self.config,
            text,
            labels,
            descriptions,
        )?;
        let seq_len = input.input_ids.len();

        let input_ids: Array2<i64> = Array2::from_shape_vec(
            (1, seq_len),
            input.input_ids.iter().map(|&id| id as i64).collect(),
        )
        .expect("input_ids shape");
        let attention_mask: Array2<i64> = Array2::ones((1, seq_len));

        let input_ids_t = ort::value::Value::from_array(input_ids)?;
        let mask_t = ort::value::Value::from_array(attention_mask)?;

        let (hs_shape, hs_data) = {
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
        debug_assert_eq!(hs_shape.len(), 3, "encoder output must be 3-D");
        debug_assert_eq!(hs_shape[0], 1);

        let hidden = ArrayView2::from_shape((hs_shape[1], hs_shape[2]), &hs_data)
            .expect("hidden_states reshape");

        let hidden_size = hs_shape[2];
        let mut label_emb: Array2<f32> = Array2::zeros((labels.len(), hidden_size));
        for (row, &pos) in input.label_positions.iter().enumerate() {
            label_emb
                .slice_mut(s![row, ..])
                .assign(&hidden.slice(s![pos, ..]));
        }

        let label_emb_t = ort::value::Value::from_array(label_emb)?;
        let logits: Vec<f32> = {
            let mut g = self.sessions.classifier.lock().expect("classifier mutex");
            let outputs = g.run(ort::inputs![ONNX_HIDDEN_STATE => label_emb_t])?;
            let (_shape, data) = outputs[0].try_extract_tensor::<f32>()?;
            data.to_vec()
        };

        let scores: Vec<f32> = if opts.multi_label {
            sigmoid(&logits)
        } else {
            softmax(&logits)
        };

        let mut results: Vec<Classification> = labels
            .iter()
            .zip(scores.iter())
            .map(|(&l, &s)| Classification {
                label: l.to_string(),
                score: s,
            })
            .collect();

        if opts.multi_label {
            results.retain(|c| c.score >= opts.threshold);
            results.sort_by(|a, b| {
                b.score
                    .partial_cmp(&a.score)
                    .unwrap_or(std::cmp::Ordering::Equal)
            });
            Ok(results)
        } else {
            let best = results
                .into_iter()
                .max_by(|a, b| {
                    a.score
                        .partial_cmp(&b.score)
                        .unwrap_or(std::cmp::Ordering::Equal)
                })
                .expect("non-empty labels");
            Ok(vec![best])
        }
    }
}

fn sigmoid(xs: &[f32]) -> Vec<f32> {
    xs.iter()
        .map(|&x| {
            if x >= 0.0 {
                1.0 / (1.0 + (-x).exp())
            } else {
                let e = x.exp();
                e / (1.0 + e)
            }
        })
        .collect()
}

fn softmax(xs: &[f32]) -> Vec<f32> {
    let max = xs.iter().copied().fold(f32::NEG_INFINITY, f32::max);
    let exps: Vec<f32> = xs.iter().map(|&x| (x - max).exp()).collect();
    let sum: f32 = exps.iter().sum();
    exps.into_iter().map(|e| e / sum).collect()
}
