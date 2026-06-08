"""Spawn-safety smoke for the fast_gliner runtime (worker-pool scenario).

History: the original fork-based variant of this smoke PROVED the deadlock —
a worker forked from a parent holding a live ORT session hangs on its first
Run(), even after reloading a fresh session post-fork. DetectorWorkerPool now
forces the *spawn* start method when GLiNER2 runs on fast_gliner.

This smoke reproduces that retained strategy: the parent loads a fastgliner
detector AND runs a forward (live ORT threads), then spawns workers that each
load their own session and detect.
"""
import multiprocessing as mp
import sys

from pii_detector.application.config.detection_policy import DetectionConfig
from pii_detector.infrastructure.detector.gliner2_detector import Gliner2Detector

TEXT = "Contact: Jean Dupont, jean.dupont@example.ch, IBAN CH93 0076 2011 6238 5295 7."
CONFIGS = {
    "GLINER2:EMAIL": {"enabled": True, "detector": "GLINER2",
                      "detector_label": "email address", "threshold": 0.5},
    "GLINER2:IBAN": {"enabled": True, "detector": "GLINER2",
                     "detector_label": "iban", "threshold": 0.5},
}


def worker(queue):
    # Mirrors the spawn path of detector_worker_pool._worker_init: a clean
    # interpreter loading its own detector (fresh ORT session).
    detector = Gliner2Detector(DetectionConfig())
    detector.load_model()
    entities = detector.detect_pii(TEXT, threshold=0.5, pii_type_configs=CONFIGS)
    queue.put([(e.pii_type, e.text) for e in entities])


if __name__ == "__main__":
    parent_detector = Gliner2Detector(DetectionConfig())
    parent_detector.load_model()
    assert parent_detector.runtime == "fastgliner", f"runtime={parent_detector.runtime}"
    # Parent runs one inference BEFORE spawning (live ORT threads — the exact
    # state under which fork deadlocks; spawn must be immune).
    parent_entities = parent_detector.detect_pii(
        TEXT, threshold=0.5, pii_type_configs=CONFIGS)
    print(f"PARENT: {len(parent_entities)} entities", flush=True)

    ctx = mp.get_context("spawn")
    queue = ctx.Queue()
    procs = [ctx.Process(target=worker, args=(queue,)) for _ in range(2)]
    for p in procs:
        p.start()
    for p in procs:
        p.join(timeout=240)
        if p.is_alive():
            print("SPAWN SMOKE FAILED: worker hung")
            p.terminate()
            sys.exit(1)
    results = []
    while not queue.empty():
        results.append(queue.get())

    print(f"WORKERS: {results}")
    ok = len(results) == 2 and all(len(r) == 2 for r in results)
    print("SPAWN SMOKE " + ("PASSED" if ok else "FAILED"))
    sys.exit(0 if ok else 1)
