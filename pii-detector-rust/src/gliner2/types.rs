/// A named entity extracted from text.
#[derive(Debug, Clone, PartialEq)]
pub struct Entity {
    pub text: String,
    pub label: String,
    pub start: usize,
    pub end: usize,
    pub score: f32,
}

/// A single classification result.
#[derive(Debug, Clone, PartialEq)]
pub struct Classification {
    pub label: String,
    pub score: f32,
}
