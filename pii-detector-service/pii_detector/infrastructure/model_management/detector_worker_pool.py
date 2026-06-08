"""Pool de processus d'inférence pour paralléliser les requêtes DetectPII.

Pourquoi des processus : le pipeline est dominé à ~99.7 % par le forward pass
GLiNER2 (DeBERTa-v3-large) sur CPU. L'intra-op PyTorch scale mal au-delà de
quelques threads (memory-bound) ; le data-parallélisme « N inférences
indépendantes sur N cœurs » scale quasi linéairement
(cf. benchmarks/SCALING-CONCLUSIONS.md).

Stratégie mémoire : sur Linux (container), le pool est créé par **fork après
préchargement** — le parent charge le détecteur singleton (poids fp32 ~1.8 GB)
SANS exécuter de forward, puis forke N workers qui héritent des poids en
copy-on-write : la RAM reste ~1 copie de modèle, pas N. Sur les plateformes
sans fork (Windows dev), chaque worker recharge sa copie (spawn).

Exception fast_gliner : quand GLiNER2 tourne sur le runtime fast_gliner
(gline-rs/ONNX Runtime), le pool force **spawn** même sur Linux — une session
ORT ne survit pas au fork (deadlock au premier Run() de l'enfant, y compris
après rechargement de session). Chaque worker spawn charge sa propre session
(~1.4 GB ONNX × N, pas de COW possible sur ces poids).

Activation : env ``PII_WORKER_PROCESSES`` (0/1 ou absent = pool désactivé,
comportement historique inchangé). ``TORCH_NUM_THREADS`` s'applique à chaque
worker (recommandé : 1-2 avec N workers ≈ cœurs du container).

Sécurité fork : le parent ne doit JAMAIS exécuter de forward avant le fork
(aucun thread BLAS vivant) ; chaque worker fait son propre warmup après fork.
"""
from __future__ import annotations

import logging
import multiprocessing as mp
import os
from typing import Any, List, Optional

logger = logging.getLogger(__name__)

# Détecteur du worker courant (un par processus du pool).
_WORKER_DETECTOR: Any = None


def pool_size_from_env() -> int:
    """Taille du pool depuis ``PII_WORKER_PROCESSES`` (0 = désactivé)."""
    try:
        n = int(os.getenv('PII_WORKER_PROCESSES', '0'))
    except ValueError:
        return 0
    return n if n > 1 else 0


def _worker_init(torch_threads: int) -> None:
    """Initialise le worker : threads torch, détecteur, warmup BLAS.

    En contexte fork, ``get_detector_instance`` retourne le singleton hérité du
    parent (poids partagés COW) ; en spawn, il recharge une copie complète.
    """
    global _WORKER_DETECTOR
    os.environ['OMP_NUM_THREADS'] = str(torch_threads)
    os.environ['MKL_NUM_THREADS'] = str(torch_threads)
    try:
        import torch
        torch.set_num_threads(torch_threads)
    except Exception:  # pragma: no cover - defensive
        logger.warning('worker: failed to set torch threads', exc_info=True)

    import importlib
    pii_service = importlib.import_module(
        'pii_detector.infrastructure.adapter.in.grpc.pii_service')
    _WORKER_DETECTOR = pii_service.get_detector_instance()

    # Warmup : premier forward DANS le worker (jamais dans le parent) pour
    # initialiser l'état BLAS/JIT de ce processus hors du chemin des requêtes.
    try:
        if _WORKER_DETECTOR is not None:
            _WORKER_DETECTOR.detect_pii(
                'Warmup IBAN CH9300762011623852957 tel +41215551234.', 0.5)
    except Exception:  # pragma: no cover - defensive
        logger.warning('worker warmup failed (continuing)', exc_info=True)
    logger.info('detector worker ready: pid=%d torch_threads=%d',
                os.getpid(), torch_threads)


def _worker_detect(payload) -> List:
    """Exécute detect_pii dans le worker. ``payload=(content, threshold, kwargs)``."""
    content, threshold, kwargs = payload
    return _WORKER_DETECTOR.detect_pii(content, threshold, **(kwargs or {}))


def _worker_detect_with_stats(payload):
    """Exécute la détection en remontant les stats par détecteur.

    Retourne ``(entities, detector_stats)``. ``detector_stats`` est une liste de
    dicts picklables (``source`` est un ``DetectorSource`` Enum, picklable).
    Quand le détecteur n'expose pas ``detect_pii_with_stats`` (chemin non
    composite), on retombe sur ``detect_pii`` avec des stats vides — le champ
    proto reste alors vide, ce qui est rétro-compatible.
    """
    content, threshold, kwargs = payload
    fn = getattr(_WORKER_DETECTOR, 'detect_pii_with_stats', None)
    if fn is not None:
        return fn(content, threshold, **(kwargs or {}))
    return _WORKER_DETECTOR.detect_pii(content, threshold, **(kwargs or {})), []


def _singleton_uses_fastgliner() -> bool:
    """True si le singleton (déjà chargé par pii_service) tourne sur fast_gliner."""
    try:
        import importlib
        pii_service = importlib.import_module(
            'pii_detector.infrastructure.adapter.in.grpc.pii_service')
        detector = pii_service.get_detector_instance()
    except Exception:  # pragma: no cover - defensive
        return False
    gliner2 = getattr(detector, 'gliner2_detector', None)
    return any(
        getattr(candidate, 'runtime', None) == 'fastgliner'
        for candidate in (detector, gliner2) if candidate is not None
    )


class DetectorWorkerPool:
    """Façade thread-safe au-dessus de ``multiprocessing.Pool``.

    Les threads gRPC appellent :meth:`detect` de façon bloquante ; la pool
    distribue les requêtes aux N workers (FIFO). Un worker tué (OOM) est
    remplacé automatiquement par ``multiprocessing.Pool`` (re-fork du parent,
    donc ré-héritage des poids).
    """

    def __init__(self, processes: int, torch_threads: int):
        self.processes = processes
        self.torch_threads = torch_threads
        start_method = 'fork' if 'fork' in mp.get_all_start_methods() else 'spawn'
        if start_method == 'fork' and _singleton_uses_fastgliner():
            # Une session ONNX Runtime (fast_gliner/PyO3) ne survit PAS au
            # fork : ses threads intra-op n'existent que dans le parent et le
            # premier Run() de l'enfant deadlock — recharger une session
            # fraîche post-fork deadlocke aussi (vérifié par
            # experiments/fastgliner_fork_safety_smoke.py). spawn donne à
            # chaque worker un interpréteur propre qui charge sa propre
            # session ; le bénéfice COW ne s'applique de toute façon pas aux
            # poids ONNX.
            start_method = 'spawn'
        ctx = mp.get_context(start_method)
        logger.info(
            'Starting detector worker pool: %d processes (%s), '
            'torch_threads/worker=%d', processes, start_method, torch_threads)
        self._pool = ctx.Pool(
            processes=processes,
            initializer=_worker_init,
            initargs=(torch_threads,),
        )

    def detect(self, content: str, threshold: float,
               kwargs: Optional[dict] = None) -> List:
        """Détection bloquante sur un worker du pool (appelable multi-thread)."""
        return self._pool.apply(_worker_detect, ((content, threshold, kwargs),))

    def detect_with_stats(self, content: str, threshold: float,
                          kwargs: Optional[dict] = None):
        """Détection bloquante remontant ``(entities, detector_stats)``.

        Appelable multi-thread (chaque appel sérialise sur un worker FIFO). Les
        stats traversent la frontière process par pickle (dicts + Enum).
        """
        return self._pool.apply(
            _worker_detect_with_stats, ((content, threshold, kwargs),))

    def warm_up(self) -> None:
        """Bloque jusqu'à ce que chaque worker ait terminé son init/warmup.

        ``Pool`` crée les workers immédiatement mais l'init est asynchrone ;
        on sérialise N tâches triviales pour s'assurer que le pool est
        opérationnel avant d'annoncer le serveur prêt.
        """
        results = [
            self._pool.apply_async(_worker_detect, (('ok', 0.9, {}),))
            for _ in range(self.processes)
        ]
        for r in results:
            try:
                r.get(timeout=600)
            except Exception:  # pragma: no cover - defensive
                logger.warning('worker warm_up task failed', exc_info=True)
        logger.info('Detector worker pool warmed up (%d workers)', self.processes)

    def shutdown(self) -> None:
        try:
            self._pool.terminate()
            self._pool.join()
        except Exception:  # pragma: no cover - defensive
            logger.warning('worker pool shutdown failed', exc_info=True)
