"""Velocity bench for the GLiNER2+Presidio+Regex composite across scaling levers.

Measures total chars/s on a fixed corpus sample (see ``corpus_sample.py``) and
dumps the findings (post type-config filter, replicating ``pii_service``) so
levers can be diff'ed for precision regressions.

Modes
-----
seq      1 stream, 1 model              -> intra-op only (torch_threads=T)
threads  K streams, 1 SHARED model      -> GIL released during forward passes
procs    N processes, N MODEL COPIES    -> true data parallelism, N x RAM

Extra levers
------------
--gliner2-chunk-workers C   parallelize the 384-token chunks of a SINGLE
                            document across C threads inside the detector
                            (intra-request parallelism, helps worst-case files)
--only gliner2|presidio|regex   profile a single detector's share

Usage (from pii-detector-service/, venv python):
    python benchmarks/bench_scaling.py --tag seq-t8 --mode seq --torch-threads 8 \
        --sample-dir benchmarks/.sample --seed-sql <path> --out-root benchmarks/out
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import sys
import time
from pathlib import Path

BENCH_DIR = Path(__file__).parent
SERVICE_ROOT = BENCH_DIR.parent
sys.path.insert(0, str(SERVICE_ROOT))
sys.path.insert(0, str(BENCH_DIR))

# HF cache: reuse the Testcontainers-mounted cache so no re-download happens.
# OFFLINE is mandatory on the Windows host: an online metadata check that fails
# (rate limit, transient) makes gliner2 fall back to pytorch_model.bin -> 404.
DEFAULT_HF_CACHE = str(Path.home() / '.ai-sentinel-it-hf-cache')
os.environ.setdefault('HF_HOME', DEFAULT_HF_CACHE)
os.environ.setdefault('HF_HUB_OFFLINE', '1')
# gliner2 prints an emoji banner; cp1252 consoles would crash without UTF-8.
os.environ.setdefault('PYTHONIOENCODING', 'utf-8')

logging.basicConfig(
    level=getattr(logging, os.environ.get('BENCH_LOG', 'WARNING')),
    format='%(asctime)s %(levelname)-5s %(name)s | %(message)s',
)

WARMUP_TEXT = (
    "Note interne: le client Jean Dupont (IBAN CH9300762011623852957, "
    "carte 4111 1111 1111 1111, tel +41 21 555 12 34) a signale un incident. "
    "Identifiant systeme: jdupont, mot de passe temporaire: Tmp!2024_reset. "
) * 4


def _say(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


# ----------------------------------------------------------------------------
# Pipeline construction (used by every mode; in procs mode it runs per child)
# ----------------------------------------------------------------------------

def _make_batch_class():
    from pii_detector.infrastructure.detector.gliner2_detector import Gliner2Detector

    class BatchGliner2Detector(Gliner2Detector):
        """Gliner2Detector envoyant les chunks d'un document via ``batch_extract``.

        ``batch_extract`` renvoie une liste alignée sur ``texts`` ; le rebase +
        dedup d'overlap est identique au chemin séquentiel, donc les findings
        doivent être identiques (vérifié par compare_findings).
        """

        def __init__(self, batch_size: int, config=None):
            super().__init__(config=config)
            self._batch_size = batch_size

        def _run_inference(self, text, schema, threshold):
            if self.chunker is None:
                raise RuntimeError("Chunker not initialized. Call load_model() first.")
            chunk_results = self.chunker.chunk_text(text)
            if len(chunk_results) <= 1:
                return super()._run_inference(text, schema, threshold)

            raws = self.model.batch_extract(
                [c.text for c in chunk_results],
                schema,
                batch_size=self._batch_size,
                threshold=float(threshold),
                format_results=False,
                include_confidence=True,
                include_spans=True,
            )
            aggregated, seen = [], set()
            for chunk_result, raw in zip(chunk_results, raws):
                for span in self._iter_raw_spans(raw):
                    rebased = self._rebase_span(span, chunk_result.start)
                    key = (rebased['start'], rebased['end'], rebased['label'])
                    if key in seen:
                        continue
                    seen.add(key)
                    aggregated.append(rebased)
            return aggregated

    return BatchGliner2Detector


def _quantize_gliner2_int8(composite) -> None:
    """Quantize dynamiquement les nn.Linear du modèle GLiNER2 en int8.

    Adresse le goulot bande passante mémoire (poids fp32 1.8 GB relus à chaque
    forward) : trafic ÷4 + kernels VNNI. Peut décaler les scores — la
    comparaison de findings vs baseline est obligatoire avant adoption.
    """
    import torch
    from torch.ao.quantization import quantize_dynamic

    detector = composite.gliner2_detector
    quantize_dynamic(detector.model, {torch.nn.Linear},
                     dtype=torch.qint8, inplace=True)
    _say('GLiNER2 model dynamically quantized to int8 (nn.Linear)')


def _gliner2_config(model_id: str | None):
    """DetectionConfig forçant un model_id GLiNER2 (sinon défaut large-v1 codé en dur)."""
    if not model_id:
        return None
    from pii_detector.application.config.detection_policy import DetectionConfig
    return DetectionConfig(model_id=model_id)


def build_composite(gliner2_chunk_workers: int = 1, configs: dict | None = None,
                    gliner2_batch_size: int = 0, quantize_int8: bool = False,
                    gliner2_model: str | None = None):
    """Instantiate the real composite (GLiNER2 + Presidio + Regex), load models.

    ``configs``: full pii_type_configs dict (seed parity). Presidio normally
    re-fetches its PRESIDIO rows from the DB on EVERY request; without a DB on
    the host it would scan nothing. We wrap ``_run_presidio_detection`` to pass
    the pre-filtered PRESIDIO sub-dict explicitly (``detect_pii`` supports a
    ``pii_type_configs`` override), replicating the container behavior.

    ``gliner2_model``: override le model_id GLiNER2 (par défaut le détecteur charge
    ``fastino/gliner2-large-v1`` codé en dur ; aucun toml n'est lu).
    """
    from pii_detector.application.orchestration.composite_detector import (
        CompositePIIDetector,
    )
    from pii_detector.infrastructure.detector.gliner2_detector import Gliner2Detector
    from pii_detector.infrastructure.detector.presidio_detector import PresidioDetector
    from pii_detector.infrastructure.detector.regex_detector import RegexDetector

    g2_config = _gliner2_config(gliner2_model)
    if gliner2_batch_size > 0:
        batch_cls = _make_batch_class()
        gliner2 = batch_cls(batch_size=gliner2_batch_size, config=g2_config)
    elif gliner2_chunk_workers > 1:
        chunk_parallel_cls = _make_chunk_parallel_class()
        gliner2 = chunk_parallel_cls(chunk_workers=gliner2_chunk_workers, config=g2_config)
    else:
        gliner2 = Gliner2Detector(config=g2_config)

    composite = CompositePIIDetector(
        ml_detector=None,
        regex_detector=RegexDetector(),
        presidio_detector=PresidioDetector(),
        gliner2_detector=gliner2,
        enable_regex=True,
        enable_presidio=True,
        enable_openmed=False,
        enable_gliner2=True,
    )
    composite.load_model()
    if quantize_int8:
        _quantize_gliner2_int8(composite)

    presidio_configs = {
        key.split(':', 1)[1]: cfg for key, cfg in (configs or {}).items()
        if key.startswith('PRESIDIO:')
    }
    if presidio_configs and composite.presidio_detector is not None:
        presidio_detect = composite.presidio_detector.detect_pii

        def _run_presidio_with_configs(text, threshold):
            try:
                return presidio_detect(text, threshold,
                                       pii_type_configs=presidio_configs)
            except Exception:
                logging.getLogger(__name__).error(
                    'PRESIDIO_DETECTION_FAILED in bench wrapper', exc_info=True)
                return []

        composite._run_presidio_detection = _run_presidio_with_configs
    return composite


def _make_chunk_parallel_class():
    from concurrent.futures import ThreadPoolExecutor

    from pii_detector.infrastructure.detector.gliner2_detector import Gliner2Detector

    class ChunkParallelGliner2Detector(Gliner2Detector):
        """Gliner2Detector running its per-document chunks on C threads.

        ``ThreadPoolExecutor.map`` preserves chunk order, so the overlap
        dedup behaves byte-for-byte like the sequential implementation.
        """

        def __init__(self, chunk_workers: int, config=None):
            super().__init__(config=config)
            self._chunk_workers = chunk_workers

        def _run_inference(self, text, schema, threshold):
            if self.chunker is None:
                raise RuntimeError("Chunker not initialized. Call load_model() first.")
            chunk_results = self.chunker.chunk_text(text)
            if len(chunk_results) <= 1 or self._chunk_workers <= 1:
                return super()._run_inference(text, schema, threshold)

            def _one(chunk_result):
                raw = self.model.extract(
                    chunk_result.text,
                    schema,
                    threshold=float(threshold),
                    format_results=False,
                    include_confidence=True,
                    include_spans=True,
                )
                return chunk_result.start, raw

            aggregated, seen = [], set()
            with ThreadPoolExecutor(max_workers=self._chunk_workers) as pool:
                for start, raw in pool.map(_one, chunk_results):
                    for span in self._iter_raw_spans(raw):
                        rebased = self._rebase_span(span, start)
                        key = (rebased['start'], rebased['end'], rebased['label'])
                        if key in seen:
                            continue
                        seen.add(key)
                        aggregated.append(rebased)
            return aggregated

    return ChunkParallelGliner2Detector


# ----------------------------------------------------------------------------
# Entity normalization + type-config post-filter (pii_service replica)
# ----------------------------------------------------------------------------

def _normalize_type(pii_type) -> str:
    if pii_type is None or pii_type == '':
        return 'UNKNOWN'
    name = getattr(pii_type, 'name', None)
    if name is not None and not isinstance(pii_type, str):
        return name
    return str(pii_type).upper().replace(' ', '_').replace('-', '_')


def entity_to_dict(rel: str, entity) -> dict:
    source = getattr(entity, 'source', None)
    source_str = getattr(source, 'name', None) or str(source or 'UNKNOWN').upper()
    return {
        'file': rel,
        'pii_type': _normalize_type(getattr(entity, 'pii_type', None)),
        'type_label': str(getattr(entity, 'type_label', '')),
        'start': int(entity.start),
        'end': int(entity.end),
        'score': round(float(entity.score), 6),
        'source': source_str,
        'text': str(entity.text)[:80],
    }


def apply_type_config_filter(entities: list, configs: dict) -> list:
    """Replicates PIIDetectionServicer._filter_entities_by_type_config."""
    kept = []
    for e in entities:
        etype = e['pii_type']
        cfg = configs.get(f"{e['source']}:{etype}") or configs.get(etype)
        if not cfg:
            kept.append(e)
            continue
        cfg_detector = cfg.get('detector', 'ALL')
        if cfg_detector != 'ALL' and cfg_detector != e['source']:
            kept.append(e)
            continue
        if not cfg.get('enabled', True):
            continue
        if e['score'] < float(cfg.get('threshold', 0.5)):
            continue
        kept.append(e)
    return kept


# ----------------------------------------------------------------------------
# Scan of one file (shared by all modes)
# ----------------------------------------------------------------------------

def scan_one(composite, rel: str, text: str, run_cfg: dict) -> dict:
    t0 = time.perf_counter()
    entities = composite.detect_pii(
        text,
        threshold=run_cfg['threshold'],
        enable_ml=False,
        enable_regex=run_cfg['regex'],
        enable_presidio=run_cfg['presidio'],
        enable_openmed=False,
        enable_gliner2=run_cfg['gliner2'],
        pii_type_configs=run_cfg['configs'],
    )
    elapsed = time.perf_counter() - t0
    dicts = apply_type_config_filter(
        [entity_to_dict(rel, e) for e in entities], run_cfg['configs'])
    return {'rel': rel, 'chars': len(text), 'ms': round(elapsed * 1000, 1),
            'entities': dicts}


# ----------------------------------------------------------------------------
# procs mode: per-child globals
# ----------------------------------------------------------------------------

_WORKER: dict = {}


def _proc_init(torch_threads: int, chunk_workers: int, batch_size: int,
               quantize_int8: bool, gliner2_model, run_cfg: dict, hf_home: str,
               ready_barrier):
    os.environ['HF_HOME'] = hf_home
    os.environ['OMP_NUM_THREADS'] = str(torch_threads)
    os.environ['MKL_NUM_THREADS'] = str(torch_threads)
    # Spawn (Windows) : chaque worker charge SA copie (1.8 GB + ~2x transitoire).
    # N chargements simultanés saturent le commit Windows (thrash pagefile,
    # BrokenBarrierError observé à N=8/12). Étalement en 3 vagues par un sleep
    # déterministe (pid) — un 2e proxy Manager (Semaphore) dans initargs
    # deadlockait le bootstrap spawn, donc pas de primitive partagée ici.
    # Sans objet en prod : le container Linux fork APRÈS un unique chargement.
    time.sleep((os.getpid() % 3) * 50)
    import torch
    torch.set_num_threads(torch_threads)
    logging.getLogger().setLevel(logging.WARNING)
    t0 = time.perf_counter()
    composite = build_composite(chunk_workers, run_cfg['configs'], batch_size,
                                quantize_int8, gliner2_model)
    # Warmup inside the child so BLAS/JIT costs stay out of the measure.
    scan_one(composite, '_warmup_', WARMUP_TEXT, run_cfg)
    _WORKER['composite'] = composite
    _WORKER['run_cfg'] = run_cfg
    print(f"[worker pid={os.getpid()}] model loaded+warm in "
          f"{time.perf_counter() - t0:.1f}s", flush=True)
    # All workers (and the parent) meet here so the measured run starts with
    # every model copy loaded — keeps the wall-clock fair.
    ready_barrier.wait(timeout=1800)


def _proc_scan(task):
    rel, text = task
    return scan_one(_WORKER['composite'], rel, text, _WORKER['run_cfg'])


# ----------------------------------------------------------------------------
# Runners
# ----------------------------------------------------------------------------

def run_seq(files, run_cfg, composite):
    results = []
    for i, (rel, text) in enumerate(files, 1):
        r = scan_one(composite, rel, text, run_cfg)
        results.append(r)
        _say(f"  [{i}/{len(files)}] {r['chars']:>7} chars in {r['ms'] / 1000:.1f}s "
             f"({r['chars'] / max(r['ms'] / 1000, 1e-3):,.0f} c/s) {rel}")
    return results


def run_threads(files, run_cfg, composite, workers: int):
    from concurrent.futures import ThreadPoolExecutor, as_completed
    results = []
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(scan_one, composite, rel, text, run_cfg): rel
                   for rel, text in files}
        for i, fut in enumerate(as_completed(futures), 1):
            r = fut.result()
            results.append(r)
            _say(f"  [{i}/{len(files)}] done {r['rel']} ({r['chars']} chars, "
                 f"{r['ms'] / 1000:.1f}s)")
    return results


def run_procs(files, run_cfg, workers: int, torch_threads: int, chunk_workers: int,
              batch_size: int, quantize_int8: bool, gliner2_model):
    import multiprocessing as mp
    ctx = mp.get_context('spawn')
    manager = ctx.Manager()
    ready_barrier = manager.Barrier(workers + 1)
    _say(f"spawning {workers} workers (loads étalés en 3 vagues — voir _proc_init)...")
    with ctx.Pool(processes=workers, initializer=_proc_init,
                  initargs=(torch_threads, chunk_workers, batch_size, quantize_int8,
                            gliner2_model, run_cfg, os.environ['HF_HOME'],
                            ready_barrier)) as pool:
        ready_barrier.wait(timeout=1800)
        _say("workers ready — starting measured run")
        t0 = time.perf_counter()
        results = []
        for i, r in enumerate(pool.imap_unordered(_proc_scan, files, chunksize=1), 1):
            results.append(r)
            _say(f"  [{i}/{len(files)}] done {r['rel']} ({r['chars']} chars, "
                 f"{r['ms'] / 1000:.1f}s)")
        wall = time.perf_counter() - t0
    return results, wall


# ----------------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------------

def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument('--tag', required=True)
    ap.add_argument('--mode', choices=['seq', 'threads', 'procs'], required=True)
    ap.add_argument('--workers', type=int, default=1)
    ap.add_argument('--torch-threads', type=int, default=1)
    ap.add_argument('--gliner2-chunk-workers', type=int, default=1)
    ap.add_argument('--gliner2-batch-size', type=int, default=0,
                    help='>0: route les chunks via batch_extract(batch_size=N)')
    ap.add_argument('--gliner2-quantize', choices=['none', 'int8'], default='none',
                    help='int8: quantize_dynamic des nn.Linear (bande passante /4)')
    ap.add_argument('--gliner2-model', default=None,
                    help='override le model_id GLiNER2 (def: large-v1 codé en dur)')
    ap.add_argument('--only', choices=['all', 'gliner2', 'presidio', 'regex'],
                    default='all')
    ap.add_argument('--sample-dir', required=True)
    ap.add_argument('--seed-sql', required=True)
    ap.add_argument('--out-root', required=True)
    args = ap.parse_args()

    from corpus_sample import load_sample
    from seed_config_parser import parse_seed

    flags, default_threshold, configs = parse_seed(Path(args.seed_sql))
    run_cfg = {
        'threshold': default_threshold,
        'configs': configs,
        'gliner2': flags.get('gliner2_enabled', True),
        'presidio': flags.get('presidio_enabled', True),
        'regex': flags.get('regex_enabled', True),
    }
    if args.only != 'all':
        run_cfg['gliner2'] = args.only == 'gliner2'
        run_cfg['presidio'] = args.only == 'presidio'
        run_cfg['regex'] = args.only == 'regex'

    files = load_sample(Path(args.sample_dir))
    total_chars = sum(len(t) for _, t in files)
    out_dir = Path(args.out_root) / args.tag
    out_dir.mkdir(parents=True, exist_ok=True)

    _say(f"=== {args.tag} === mode={args.mode} workers={args.workers} "
         f"torch_threads={args.torch_threads} chunk_workers={args.gliner2_chunk_workers} "
         f"only={args.only} files={len(files)} chars={total_chars:,} "
         f"threshold={default_threshold}")

    model_load_s = None
    if args.mode in ('seq', 'threads'):
        os.environ['OMP_NUM_THREADS'] = str(args.torch_threads)
        os.environ['MKL_NUM_THREADS'] = str(args.torch_threads)
        import torch
        torch.set_num_threads(args.torch_threads)
        t0 = time.perf_counter()
        composite = build_composite(args.gliner2_chunk_workers, run_cfg['configs'],
                                    args.gliner2_batch_size,
                                    args.gliner2_quantize == 'int8',
                                    args.gliner2_model)
        model_load_s = round(time.perf_counter() - t0, 1)
        _say(f"composite loaded in {model_load_s}s "
             f"(torch effective threads={torch.get_num_threads()})")
        # Warmups: one per worker thread to settle BLAS/thread state.
        n_warm = args.workers if args.mode == 'threads' else 1
        from concurrent.futures import ThreadPoolExecutor
        with ThreadPoolExecutor(max_workers=max(1, n_warm)) as pool:
            list(pool.map(lambda _:
                          scan_one(composite, '_warmup_', WARMUP_TEXT, run_cfg),
                          range(n_warm)))
        _say("warmup done — starting measured run")
        t0 = time.perf_counter()
        if args.mode == 'seq':
            results = run_seq(files, run_cfg, composite)
        else:
            results = run_threads(files, run_cfg, composite, args.workers)
        wall = time.perf_counter() - t0
    else:
        results, wall = run_procs(files, run_cfg, args.workers,
                                  args.torch_threads, args.gliner2_chunk_workers,
                                  args.gliner2_batch_size,
                                  args.gliner2_quantize == 'int8',
                                  args.gliner2_model)

    # RSS (parent + children).
    rss_mb = None
    try:
        import psutil
        proc = psutil.Process()
        rss = proc.memory_info().rss
        for child in proc.children(recursive=True):
            try:
                rss += child.memory_info().rss
            except psutil.Error:
                pass
        rss_mb = round(rss / 1024 / 1024)
    except Exception:
        pass

    chars_per_s = total_chars / wall if wall > 0 else 0.0
    findings = sorted(
        (e for r in results for e in r['entities']),
        key=lambda e: (e['file'], e['start'], e['end'], e['pii_type'], e['source']))

    metrics = {
        'tag': args.tag,
        'mode': args.mode,
        'workers': args.workers,
        'torch_threads': args.torch_threads,
        'gliner2_chunk_workers': args.gliner2_chunk_workers,
        'gliner2_batch_size': args.gliner2_batch_size,
        'gliner2_quantize': args.gliner2_quantize,
        'gliner2_model': args.gliner2_model or 'fastino/gliner2-large-v1 (default)',
        'only': args.only,
        'n_files': len(files),
        'total_chars': total_chars,
        'wall_s': round(wall, 2),
        'chars_per_s': round(chars_per_s, 1),
        'n_findings': len(findings),
        'model_load_s': model_load_s,
        'rss_mb': rss_mb,
        'per_file': [{k: r[k] for k in ('rel', 'chars', 'ms')} |
                     {'n_entities': len(r['entities'])} for r in results],
    }
    (out_dir / 'metrics.json').write_text(
        json.dumps(metrics, indent=2, ensure_ascii=False), encoding='utf-8')
    with (out_dir / 'findings.jsonl').open('w', encoding='utf-8') as f:
        for e in findings:
            f.write(json.dumps(e, ensure_ascii=False) + '\n')

    _say(f"=== {args.tag} DONE === wall={wall:.1f}s -> {chars_per_s:,.0f} chars/s, "
         f"{len(findings)} findings, rss={rss_mb}MB -> {out_dir}")


if __name__ == '__main__':
    main()
