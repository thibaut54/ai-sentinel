//! HTML → plain text for the benchmark replay tool.
//!
//! Mirrors the kind of output BeautifulSoup's `.get_text(separator=" ", strip=True)`
//! produces: visible text content with block-level boundaries replaced by
//! spaces, `<script>` / `<style>` subtrees dropped, whitespace collapsed.
//!
//! **Not used by the production library** — the Java backend hands us
//! already-stripped plain text. This module exists so the benchmark
//! replay tool can ingest the Confluence HTML corpus without an external
//! preprocessing step.

use ego_tree::NodeRef;
use scraper::{Html, Node};

/// Strip an HTML document to plain text.
pub fn strip(html: &str) -> String {
    let doc = Html::parse_document(html);
    let mut out = String::new();
    walk(*doc.root_element(), &mut out);
    collapse_whitespace(&out)
}

fn walk(node: NodeRef<Node>, out: &mut String) {
    for child in node.children() {
        match child.value() {
            Node::Text(t) => {
                out.push_str(&t);
            }
            Node::Element(elem) => {
                let tag = elem.name();
                if matches!(tag, "script" | "style" | "noscript" | "template") {
                    continue;
                }
                let is_block = is_block_level(tag);
                let is_break = matches!(tag, "br" | "hr");

                if (is_block || is_break) && !ends_with_ws(out) && !out.is_empty() {
                    out.push(' ');
                }

                walk(child, out);

                if is_block && !ends_with_ws(out) {
                    out.push(' ');
                }
                if is_break {
                    out.push(' ');
                }
            }
            _ => {}
        }
    }
}

fn is_block_level(tag: &str) -> bool {
    matches!(
        tag,
        "address"
            | "article"
            | "aside"
            | "blockquote"
            | "body"
            | "details"
            | "dialog"
            | "div"
            | "dl"
            | "dt"
            | "dd"
            | "fieldset"
            | "figure"
            | "footer"
            | "form"
            | "h1"
            | "h2"
            | "h3"
            | "h4"
            | "h5"
            | "h6"
            | "header"
            | "hgroup"
            | "html"
            | "li"
            | "main"
            | "nav"
            | "ol"
            | "p"
            | "pre"
            | "section"
            | "summary"
            | "table"
            | "tbody"
            | "td"
            | "th"
            | "thead"
            | "tfoot"
            | "tr"
            | "ul"
    )
}

fn ends_with_ws(s: &str) -> bool {
    s.chars().next_back().map(char::is_whitespace).unwrap_or(false)
}

fn collapse_whitespace(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut prev_ws = false;
    for c in s.chars() {
        if c.is_whitespace() {
            if !prev_ws {
                out.push(' ');
                prev_ws = true;
            }
        } else {
            out.push(c);
            prev_ws = false;
        }
    }
    out.trim().to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn drops_tags_preserves_text() {
        let html = "<p>Hello <strong>world</strong>!</p>";
        let txt = strip(html);
        assert!(txt.contains("Hello"));
        assert!(txt.contains("world"));
        assert!(!txt.contains('<'));
    }

    #[test]
    fn drops_script_and_style() {
        let html = "<p>Visible</p><script>alert('hi');</script><style>p { color: red }</style>";
        let txt = strip(html);
        assert!(txt.contains("Visible"));
        assert!(!txt.contains("alert"));
        assert!(!txt.contains("color: red"));
    }

    #[test]
    fn block_elements_get_whitespace_between() {
        let html = "<p>First</p><p>Second</p>";
        let txt = strip(html);
        // Some kind of whitespace between First and Second
        assert!(txt.contains("First"));
        assert!(txt.contains("Second"));
        let f = txt.find("First").unwrap();
        let s = txt.find("Second").unwrap();
        let between = &txt[f + 5..s];
        assert!(between.chars().any(char::is_whitespace));
    }

    #[test]
    fn table_cells_separated() {
        let html = "<table><tr><td>756.1234.5678.97</td><td>alice@example.ch</td></tr></table>";
        let txt = strip(html);
        assert!(txt.contains("756.1234.5678.97"));
        assert!(txt.contains("alice@example.ch"));
    }

    #[test]
    fn whitespace_collapses() {
        let html = "<p>Hello    \n\n\t   world</p>";
        let txt = strip(html);
        assert_eq!(txt, "Hello world");
    }

    #[test]
    fn entities_decoded() {
        let html = "<p>Soci&eacute;t&eacute; &amp; co</p>";
        let txt = strip(html);
        assert!(txt.contains("Société"));
        assert!(txt.contains("&"));
        assert!(!txt.contains("&amp;"));
    }

    #[test]
    fn br_introduces_whitespace() {
        let html = "<p>Line one<br/>Line two</p>";
        let txt = strip(html);
        assert!(txt.contains("Line one"));
        assert!(txt.contains("Line two"));
        assert!(txt.find("Line one").unwrap() + 8 < txt.find("Line two").unwrap());
    }
}
