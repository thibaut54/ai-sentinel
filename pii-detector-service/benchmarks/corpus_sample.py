"""Build a deterministic text sample from the IT corpus for velocity benches.

Extracts plain text from ``page.html`` files (stdlib HTMLParser, script/style
dropped, whitespace collapsed — close enough to the Java HtmlContentParser for
throughput purposes; lever comparisons always run on the SAME extracted texts,
so absolute extraction fidelity does not bias the comparison).

Files are picked round-robin across PII-type folders (size variety preserved)
until ``--target-chars`` is reached, then cached as ``.txt`` + ``manifest.json``
so every lever run reuses the exact same inputs.
"""
from __future__ import annotations

import argparse
import json
import re
from html.parser import HTMLParser
from pathlib import Path
from typing import List, Tuple

_SKIP_TAGS = {'script', 'style', 'noscript', 'template'}


class _TextExtractor(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._chunks: List[str] = []
        self._skip_depth = 0

    def handle_starttag(self, tag, attrs):
        if tag in _SKIP_TAGS:
            self._skip_depth += 1

    def handle_endtag(self, tag):
        if tag in _SKIP_TAGS and self._skip_depth > 0:
            self._skip_depth -= 1

    def handle_data(self, data):
        if self._skip_depth == 0 and data:
            self._chunks.append(data)

    def text(self) -> str:
        raw = ' '.join(self._chunks)
        return re.sub(r'\s+', ' ', raw).strip()


def html_to_text(html: str) -> str:
    parser = _TextExtractor()
    parser.feed(html)
    return parser.text()


def collect_pages(corpus_dir: Path) -> List[Tuple[str, Path]]:
    """All ``page.html`` as ``(relpath, path)``, sorted for determinism."""
    pages = []
    for p in sorted(corpus_dir.rglob('page.html')):
        rel = p.relative_to(corpus_dir).as_posix()
        pages.append((rel, p))
    return pages


def build_sample(corpus_dir: Path, target_chars: int, out_dir: Path,
                 max_file_chars: int = 0) -> dict:
    """``max_file_chars > 0`` truncates each file's text (word boundary) so the
    sample contains many medium files instead of one dominating page — needed
    for file-level parallel levers to expose their true scaling."""
    out_dir.mkdir(parents=True, exist_ok=True)

    # Group by top-level PII folder for round-robin variety.
    by_folder: dict = {}
    for rel, path in collect_pages(corpus_dir):
        folder = rel.split('/', 1)[0]
        by_folder.setdefault(folder, []).append((rel, path))

    selected: List[dict] = []
    total = 0
    folders = sorted(by_folder)
    idx = {f: 0 for f in folders}
    exhausted = set()

    while total < target_chars and len(exhausted) < len(folders):
        for folder in folders:
            if total >= target_chars:
                break
            entries = by_folder[folder]
            while idx[folder] < len(entries):
                rel, path = entries[idx[folder]]
                idx[folder] += 1
                text = html_to_text(path.read_text(encoding='utf-8', errors='replace'))
                if len(text) < 200:
                    continue  # quasi-empty page: skip
                if max_file_chars and len(text) > max_file_chars:
                    cut = text.rfind(' ', 0, max_file_chars)
                    text = text[:cut if cut > 0 else max_file_chars]
                safe_name = rel.replace('/', '__').replace('page.html', 'page.txt')
                (out_dir / safe_name).write_text(text, encoding='utf-8')
                selected.append({'rel': rel, 'file': safe_name, 'chars': len(text)})
                total += len(text)
                break
            else:
                exhausted.add(folder)

    manifest = {
        'corpus_dir': str(corpus_dir),
        'target_chars': target_chars,
        'total_chars': total,
        'n_files': len(selected),
        'files': selected,
    }
    (out_dir / 'manifest.json').write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False), encoding='utf-8')
    return manifest


def load_sample(sample_dir: Path) -> List[Tuple[str, str]]:
    """Return ``[(relpath, text), ...]`` in manifest order."""
    manifest = json.loads((sample_dir / 'manifest.json').read_text(encoding='utf-8'))
    out = []
    for entry in manifest['files']:
        text = (sample_dir / entry['file']).read_text(encoding='utf-8')
        out.append((entry['rel'], text))
    return out


if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--corpus-dir', required=True)
    ap.add_argument('--target-chars', type=int, default=80_000)
    ap.add_argument('--max-file-chars', type=int, default=0)
    ap.add_argument('--out-dir', required=True)
    args = ap.parse_args()

    m = build_sample(Path(args.corpus_dir), args.target_chars, Path(args.out_dir),
                     args.max_file_chars)
    print(json.dumps({k: m[k] for k in ('total_chars', 'n_files')}, indent=2))
    for f in m['files']:
        print(f"  {f['chars']:>8} chars  {f['rel']}")
