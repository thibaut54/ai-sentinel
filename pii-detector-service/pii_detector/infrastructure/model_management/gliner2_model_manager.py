"""GLiNER2 model management for PII detection.

Distinct from :class:`GLiNERModelManager`: the ``gliner2`` library is a separate
package from ``gliner`` (different load API and tokenizer access). GLiNER2 wraps
a ``microsoft/mdeberta-v3-base`` encoder behind a multi-task head.

Two runtimes are supported (selected at load time, exposed via :attr:`runtime`):

- ``"fastgliner"`` (preferred): ``fast_gliner`` Python bindings over the Rust
  inference engine gline-rs + ONNX Runtime. No torch on the inference path,
  ~4x faster on CPU. Requires a *monolithic* ONNX export of the checkpoint
  (produced by ``scripts/export_gliner2_to_monolithic_onnx.py``) resolved by
  :meth:`_resolve_onnx_dir`. Behavioural limits vs the PyTorch pipeline:
  the gline-rs decoder hard-codes a 0.5 probability floor (bindings use
  ``Parameters::default()``), and per-label *descriptions* are not part of
  the prompt (labels only).
- ``"pytorch"`` (fallback): historical ``GLiNER2.from_pretrained`` path.

POC L0 (2026-05-31) confirmed on ``fastino/gliner2-large-v1``:
  - ``GLiNER2.from_pretrained`` loads in ~5s on CPU.
  - the object exposes no usable ``.tokenizer`` attribute, so we fall back to
    ``AutoTokenizer.from_pretrained(model_id)`` (returns a fast tokenizer).
"""
from __future__ import annotations

import contextlib
import io
import logging
import os
import shutil
import tempfile
from pathlib import Path
from typing import Any, Optional

from pii_detector.application.config.detection_policy import DetectionConfig
from pii_detector.domain.exception.exceptions import ModelLoadError

# "fastgliner" (default, with silent fallback to pytorch when unavailable)
# or "pytorch" to force the historical runtime.
GLINER2_RUNTIME_ENV = "GLINER2_RUNTIME"
# Explicit directory containing the monolithic ONNX export (onnx/model.onnx
# + tokenizer.json). Overrides the conventional locations below.
GLINER2_ONNX_DIR_ENV = "GLINER2_ONNX_MODEL_DIR"
# Conventional export directory name, looked up under $HF_HOME (the cache
# volume already mounted by the IT harness and docker-compose) and under the
# local ./models directory (host development).
ONNX_EXPORT_DIRNAME = "gliner2-privacy-filter-onnx"


class Gliner2ModelManager:
    """Handles GLiNER2 model downloading and loading operations."""

    def __init__(self, config: DetectionConfig):
        self.config = config
        self.logger = logging.getLogger(f"{__name__}.{self.__class__.__name__}")
        self._model: Any = None
        self._onnx_dir: Optional[Path] = None
        self.runtime: str = "pytorch"

    def download_model(self) -> None:
        """GLiNER2 downloads weights lazily on first ``from_pretrained`` call."""
        self.logger.info(
            "GLiNER2 model %s will be downloaded on first load", self.config.model_id
        )

    def load_model(self) -> Any:
        """Load the model, preferring the fast_gliner (ONNX) runtime.

        Runtime selection follows ``env > TOML > code default``: the
        ``GLINER2_RUNTIME`` env var wins, else ``[gliner2].runtime`` from
        ``detection-settings.toml`` (carried on ``config.gliner2_runtime``),
        else the ``"fastgliner"`` code default.

        Given the effective choice:
        1. ``"pytorch"`` -> the historical ``gliner2`` PyTorch path.
        2. ``"fastgliner"`` (or default) -> fast_gliner + a resolvable ONNX
           export -> ``runtime == "fastgliner"``; otherwise fall back to
           PyTorch. The fallback is silent EXCEPT when the env var explicitly
           forced ``fastgliner``, in which case the failure is raised — an
           operator demanding the fast path in a deployment must be told it is
           unavailable rather than silently served the slower runtime.

        Returns:
            Loaded model instance (``FastGLiNER2`` or ``GLiNER2``).

        Raises:
            ModelLoadError: if no runtime could be loaded.
        """
        env_choice = os.environ.get(GLINER2_RUNTIME_ENV, "").strip().lower()
        toml_choice = (getattr(self.config, "gliner2_runtime", None) or "").strip().lower()
        effective = env_choice or toml_choice or "fastgliner"

        if effective != "pytorch":
            # strict (raise on failure) only when forced through the env var.
            model = self._try_load_fastgliner(strict=env_choice == "fastgliner")
            if model is not None:
                return model

        return self._load_pytorch()

    def get_tokenizer(self) -> Any:
        """Return a fast tokenizer for subword chunking.

        fastgliner runtime: load from the ONNX export directory (the exporter
        saves ``tokenizer.json`` + ``tokenizer_config.json`` next to ``onnx/``).
        pytorch runtime: GLiNER2 object first (no reliable attribute as of lib
        1.3.1), then ``AutoTokenizer.from_pretrained(model_id)``.
        """
        from transformers import AutoTokenizer

        if self.runtime == "fastgliner" and self._onnx_dir is not None:
            self.logger.debug("Loading tokenizer from ONNX export %s", self._onnx_dir)
            return AutoTokenizer.from_pretrained(str(self._onnx_dir))

        for attr in ("tokenizer",):
            candidate = getattr(self._model, attr, None)
            if candidate is not None and hasattr(candidate, "__call__"):
                self.logger.debug("Using GLiNER2.%s as tokenizer", attr)
                return candidate

        self.logger.debug(
            "GLiNER2 exposes no usable tokenizer attribute; "
            "falling back to AutoTokenizer for %s",
            self.config.model_id,
        )
        return AutoTokenizer.from_pretrained(self.config.model_id)

    # ------------------------------------------------------------------
    # Runtime loaders
    # ------------------------------------------------------------------

    def _try_load_fastgliner(self, strict: bool) -> Optional[Any]:
        """Attempt the fast_gliner runtime; return None to allow fallback."""
        onnx_dir = self._resolve_onnx_dir()
        if onnx_dir is None:
            message = (
                "No monolithic GLiNER2 ONNX export found (checked %s, "
                "$HF_HOME/%s, models/%s) — falling back to PyTorch runtime"
                % (GLINER2_ONNX_DIR_ENV, ONNX_EXPORT_DIRNAME, ONNX_EXPORT_DIRNAME)
            )
            if strict:
                raise ModelLoadError(message)
            self.logger.info(message)
            return None

        try:
            from fast_gliner import FastGLiNER2
        except ImportError as exc:
            message = f"fast_gliner not installed ({exc}) — falling back to PyTorch runtime"
            if strict:
                raise ModelLoadError(message) from exc
            self.logger.info(message)
            return None

        # Load from a container-local staging copy, NOT the original directory:
        # exports usually live on a bind mount (Testcontainers HF cache,
        # compose volume) and N spawn workers reading the 1.2 GB model.onnx
        # concurrently through gRPC-FUSE get truncated reads -> ort
        # "Protobuf parsing failed". The first process copies the export to
        # local disk under an inter-process lock; the others wait then load
        # the same staged copy.
        load_dir = self._stage_onnx_export(onnx_dir)
        try:
            self.logger.info("Loading GLiNER2 (fast_gliner/ONNX) from %s", load_dir)
            self._model = FastGLiNER2.from_pretrained(str(load_dir))
        except Exception as exc:
            if load_dir != onnx_dir:
                # A corrupt staging copy must not poison every later start.
                shutil.rmtree(load_dir, ignore_errors=True)
            message = f"fast_gliner failed to load {load_dir}: {exc}"
            if strict:
                raise ModelLoadError(message) from exc
            self.logger.warning("%s — falling back to PyTorch runtime", message)
            return None

        self._onnx_dir = load_dir
        self.runtime = "fastgliner"
        self.logger.info("GLiNER2 fast_gliner runtime loaded successfully")
        return self._model

    def _stage_onnx_export(self, onnx_dir: Path) -> Path:
        """Copy the ONNX export to container-local disk, serialized by a lock.

        Returns the staged directory, or ``onnx_dir`` unchanged when staging
        is unavailable (no fcntl, e.g. Windows dev) or fails (disk full) — the
        caller then loads from the original location as before.
        """
        try:
            import fcntl
        except ImportError:
            return onnx_dir

        staging_root = Path(tempfile.gettempdir()) / "gliner2-onnx-staging"
        staged_dir = staging_root / onnx_dir.name
        if staged_dir == onnx_dir:
            return onnx_dir
        try:
            staging_root.mkdir(parents=True, exist_ok=True)
            lock_path = staging_root / f"{onnx_dir.name}.lock"
            with open(lock_path, "w", encoding="utf-8") as lock_file:
                fcntl.flock(lock_file, fcntl.LOCK_EX)
                # Up to 2 attempts: a transient truncated read from the FUSE
                # mount yields a SMALLER file, which the size check catches.
                for attempt in (1, 2):
                    if self._staged_export_is_valid(onnx_dir, staged_dir):
                        return staged_dir
                    self.logger.info(
                        "Staging GLiNER2 ONNX export %s -> %s (attempt %d)",
                        onnx_dir, staged_dir, attempt)
                    shutil.rmtree(staged_dir, ignore_errors=True)
                    shutil.copytree(onnx_dir, staged_dir)
                if self._staged_export_is_valid(onnx_dir, staged_dir):
                    return staged_dir
            self.logger.warning(
                "Staged ONNX copy still incomplete after retry — loading "
                "directly from %s", onnx_dir)
            shutil.rmtree(staged_dir, ignore_errors=True)
            return onnx_dir
        except Exception:
            self.logger.warning(
                "Failed to stage ONNX export to local disk — loading directly "
                "from %s", onnx_dir, exc_info=True)
            shutil.rmtree(staged_dir, ignore_errors=True)
            return onnx_dir

    @staticmethod
    def _staged_export_is_valid(onnx_dir: Path, staged_dir: Path) -> bool:
        """True when every source file exists in the staging with equal size."""
        if not staged_dir.is_dir():
            return False
        for source in onnx_dir.rglob("*"):
            if not source.is_file():
                continue
            staged = staged_dir / source.relative_to(onnx_dir)
            if not staged.is_file() or staged.stat().st_size != source.stat().st_size:
                return False
        return True

    def _load_pytorch(self) -> Any:
        self.logger.info("Loading GLiNER2 model (PyTorch): %s", self.config.model_id)
        try:
            from gliner2 import GLiNER2

            # gliner2 (>=1.3) prints a config banner containing an emoji via
            # plain print(); on a Windows cp1252 console this raises
            # UnicodeEncodeError and aborts loading. Swallow that chatty stdout
            # into an in-memory buffer so loading is encoding-independent.
            with contextlib.redirect_stdout(io.StringIO()):
                self._model = GLiNER2.from_pretrained(self.config.model_id)
            self.runtime = "pytorch"
            self.logger.info("GLiNER2 model loaded successfully")
            return self._model
        except ImportError as exc:
            self.logger.error("gliner2 library not installed")
            raise ModelLoadError(
                "gliner2 library not installed. Install with: pip install gliner2"
            ) from exc
        except Exception as exc:
            self.logger.error("Error loading GLiNER2 model: %s", exc)
            raise ModelLoadError(f"Failed to load GLiNER2 model: {exc}") from exc

    # ------------------------------------------------------------------
    # ONNX export resolution
    # ------------------------------------------------------------------

    def _resolve_onnx_dir(self) -> Optional[Path]:
        """Locate the monolithic ONNX export directory, or None."""
        candidates = []
        env_dir = os.environ.get(GLINER2_ONNX_DIR_ENV, "").strip()
        if env_dir:
            candidates.append(Path(env_dir))
        hf_home = os.environ.get("HF_HOME", "").strip()
        if hf_home:
            candidates.append(Path(hf_home) / ONNX_EXPORT_DIRNAME)
        candidates.append(Path("models") / ONNX_EXPORT_DIRNAME)

        for candidate in candidates:
            if (candidate / "onnx" / "model.onnx").is_file():
                return candidate
            if candidate.is_dir() and next(candidate.rglob("*.onnx"), None) is not None:
                return candidate
        return None
