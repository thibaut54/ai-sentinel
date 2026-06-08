"""Repro/validation: N concurrent FastGLiNER2 loads of the same ONNX export.

Reproduces the IT failure mode: PII_WORKER_PROCESSES=8 spawn workers all load
the 1.2 GB model.onnx from the Windows bind mount (gRPC-FUSE) at the same
time; under load some get truncated reads -> ort InvalidProtobuf -> PyTorch
fallback (mixed-runtime measurements).

Modes
-----
direct   workers call FastGLiNER2.from_pretrained(<bind mount dir>) directly
         (the pre-fix code path; failures expected under contention)
manager  workers go through Gliner2ModelManager via Gliner2Detector.load_model()
         (the fixed path: flock-serialized staging copy to local disk, then
         every worker loads the staged copy; asserts runtime == fastgliner)

Usage: python fastgliner_concurrent_load_repro.py <model_dir> [n_procs] [mode]
For manager mode, point HF_HOME (or GLINER2_ONNX_MODEL_DIR) at the export.
"""
import multiprocessing as mp
import sys
import time


def load_direct(model_dir, idx, queue):
    t0 = time.perf_counter()
    try:
        from fast_gliner import FastGLiNER2
        FastGLiNER2.from_pretrained(model_dir)
        queue.put((idx, "OK", round(time.perf_counter() - t0, 1), ""))
    except Exception as exc:  # noqa: BLE001 - repro harness, report everything
        queue.put((idx, "FAIL", round(time.perf_counter() - t0, 1),
                   f"{type(exc).__name__}: {exc}"))


def load_via_manager(model_dir, idx, queue):
    t0 = time.perf_counter()
    try:
        from pii_detector.application.config.detection_policy import DetectionConfig
        from pii_detector.infrastructure.detector.gliner2_detector import (
            Gliner2Detector,
        )
        detector = Gliner2Detector(DetectionConfig())
        detector.load_model()
        status = "OK" if detector.runtime == "fastgliner" else "WRONG_RUNTIME"
        queue.put((idx, status, round(time.perf_counter() - t0, 1),
                   f"runtime={detector.runtime}"))
    except Exception as exc:  # noqa: BLE001 - repro harness, report everything
        queue.put((idx, "FAIL", round(time.perf_counter() - t0, 1),
                   f"{type(exc).__name__}: {exc}"))


if __name__ == "__main__":
    model_dir = sys.argv[1]
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 8
    mode = sys.argv[3] if len(sys.argv) > 3 else "direct"
    target = load_direct if mode == "direct" else load_via_manager

    ctx = mp.get_context("spawn")
    queue = ctx.Queue()
    procs = [ctx.Process(target=target, args=(model_dir, i, queue))
             for i in range(n)]
    for p in procs:
        p.start()
    for p in procs:
        p.join(timeout=600)
    results = []
    while not queue.empty():
        results.append(queue.get())
    results.sort()
    bad = [r for r in results if r[1] != "OK"]
    for idx, status, dt, info in results:
        print(f"  worker {idx}: {status} in {dt}s {info[:140]}")
    print(f"RESULT[{mode}]: {len(results) - len(bad)}/{len(results)} OK, "
          f"{len(bad)} BAD")
    sys.exit(1 if bad or len(results) != n else 0)
