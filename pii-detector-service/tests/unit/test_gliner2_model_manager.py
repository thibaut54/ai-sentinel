"""Unit tests for Gliner2ModelManager ONNX export staging.

Staging exists because N spawn workers loading the 1.2 GB model.onnx
concurrently through a gRPC-FUSE bind mount get truncated reads (ort
"Protobuf parsing failed"). The export is copied once to container-local
disk under an inter-process lock; every worker then loads the staged copy.

fcntl is POSIX-only, so these tests inject a no-op stand-in to exercise the
staging logic on any platform; the no-fcntl path is tested explicitly.
"""
import shutil
import sys
import types
from pathlib import Path

import pytest

from pii_detector.application.config.detection_policy import DetectionConfig
from pii_detector.infrastructure.model_management import gliner2_model_manager
from pii_detector.infrastructure.model_management.gliner2_model_manager import (
    Gliner2ModelManager,
)


@pytest.fixture
def manager():
    return Gliner2ModelManager(DetectionConfig())


@pytest.fixture
def fake_fcntl(monkeypatch):
    """No-op fcntl so the POSIX-only staging path runs on any platform."""
    module = types.SimpleNamespace(LOCK_EX=2, flock=lambda *_: None)
    monkeypatch.setitem(sys.modules, "fcntl", module)
    return module


@pytest.fixture
def export_dir(tmp_path):
    source = tmp_path / "source" / "gliner2-privacy-filter-onnx"
    (source / "onnx").mkdir(parents=True)
    (source / "onnx" / "model.onnx").write_bytes(b"x" * 64)
    (source / "tokenizer.json").write_text("{}", encoding="utf-8")
    return source


@pytest.fixture
def staging_tmp(tmp_path, monkeypatch):
    staging = tmp_path / "container-tmp"
    staging.mkdir()
    monkeypatch.setattr(
        gliner2_model_manager.tempfile, "gettempdir", lambda: str(staging))
    return staging


class TestStageOnnxExport:
    def test_Should_CopyExportToLocalStaging_When_FirstLoad(
            self, manager, export_dir, staging_tmp, fake_fcntl):
        staged = manager._stage_onnx_export(export_dir)

        assert staged != export_dir
        assert staged.is_relative_to(staging_tmp)
        assert (staged / "onnx" / "model.onnx").read_bytes() == b"x" * 64
        assert (staged / "tokenizer.json").is_file()

    def test_Should_ReuseStagedCopy_When_AlreadyValid(
            self, manager, export_dir, staging_tmp, fake_fcntl, monkeypatch):
        first = manager._stage_onnx_export(export_dir)

        copies = []
        original_copytree = shutil.copytree
        monkeypatch.setattr(
            gliner2_model_manager.shutil, "copytree",
            lambda *a, **k: copies.append(a) or original_copytree(*a, **k))
        second = manager._stage_onnx_export(export_dir)

        assert second == first
        assert copies == []

    def test_Should_RecopyStagedCopy_When_FileTruncated(
            self, manager, export_dir, staging_tmp, fake_fcntl):
        staged = manager._stage_onnx_export(export_dir)
        (staged / "onnx" / "model.onnx").write_bytes(b"x" * 10)  # truncated

        again = manager._stage_onnx_export(export_dir)

        assert (again / "onnx" / "model.onnx").read_bytes() == b"x" * 64

    def test_Should_FallBackToSource_When_CopyFails(
            self, manager, export_dir, staging_tmp, fake_fcntl, monkeypatch):
        def boom(*_a, **_k):
            raise OSError("No space left on device")
        monkeypatch.setattr(gliner2_model_manager.shutil, "copytree", boom)

        staged = manager._stage_onnx_export(export_dir)

        assert staged == export_dir

    def test_Should_FallBackToSource_When_FcntlUnavailable(
            self, manager, export_dir, staging_tmp, monkeypatch):
        # sys.modules[name] = None makes ``import fcntl`` raise ImportError —
        # the real situation on Windows hosts where fast_gliner cannot run.
        monkeypatch.setitem(sys.modules, "fcntl", None)

        staged = manager._stage_onnx_export(export_dir)

        assert staged == export_dir
        assert list(staging_tmp.iterdir()) == []


class TestRuntimeResolution:
    """``load_model`` runtime selection: env > TOML > code default.

    ``_try_load_fastgliner`` / ``_load_pytorch`` are stubbed so the test
    exercises only the resolution + the ``strict`` flag, not actual loading.
    """

    @staticmethod
    def _manager_with_runtime(runtime):
        # Mutate after construction so the test is independent of the TOML
        # file's [gliner2] default (__post_init__ would otherwise fill it).
        config = DetectionConfig()
        config.gliner2_runtime = runtime
        return Gliner2ModelManager(config)

    @staticmethod
    def _stub(manager, monkeypatch):
        calls = {"fast_strict": None, "pytorch": False}

        def fake_fast(strict):
            calls["fast_strict"] = strict
            return object()

        def fake_pytorch():
            calls["pytorch"] = True
            return object()

        monkeypatch.setattr(manager, "_try_load_fastgliner", fake_fast)
        monkeypatch.setattr(manager, "_load_pytorch", fake_pytorch)
        return calls

    def test_Should_UsePytorch_When_EnvForcesPytorch(self, monkeypatch):
        manager = self._manager_with_runtime("fastgliner")  # TOML says fast
        monkeypatch.setenv(gliner2_model_manager.GLINER2_RUNTIME_ENV, "pytorch")
        calls = self._stub(manager, monkeypatch)

        manager.load_model()

        assert calls["pytorch"] is True
        assert calls["fast_strict"] is None  # fastgliner never attempted

    def test_Should_UsePytorch_When_TomlSaysPytorchAndNoEnv(self, monkeypatch):
        manager = self._manager_with_runtime("pytorch")
        monkeypatch.delenv(gliner2_model_manager.GLINER2_RUNTIME_ENV, raising=False)
        calls = self._stub(manager, monkeypatch)

        manager.load_model()

        assert calls["pytorch"] is True
        assert calls["fast_strict"] is None

    def test_Should_LoadFastglinerStrict_When_EnvForcesFastgliner(self, monkeypatch):
        manager = self._manager_with_runtime("pytorch")  # TOML overridden by env
        monkeypatch.setenv(gliner2_model_manager.GLINER2_RUNTIME_ENV, "fastgliner")
        calls = self._stub(manager, monkeypatch)

        manager.load_model()

        assert calls["fast_strict"] is True
        assert calls["pytorch"] is False

    def test_Should_LoadFastglinerNonStrict_When_DefaultAndNoEnv(self, monkeypatch):
        manager = self._manager_with_runtime(None)  # no TOML, no env -> default
        monkeypatch.delenv(gliner2_model_manager.GLINER2_RUNTIME_ENV, raising=False)
        calls = self._stub(manager, monkeypatch)

        manager.load_model()

        assert calls["fast_strict"] is False  # silent fallback preserved
        assert calls["pytorch"] is False

    def test_Should_FallBackToPytorch_When_FastglinerUnavailableAndNotForced(
            self, monkeypatch):
        manager = self._manager_with_runtime("fastgliner")
        monkeypatch.delenv(gliner2_model_manager.GLINER2_RUNTIME_ENV, raising=False)
        monkeypatch.setattr(manager, "_try_load_fastgliner", lambda strict: None)
        loaded = object()
        monkeypatch.setattr(manager, "_load_pytorch", lambda: loaded)

        assert manager.load_model() is loaded


class TestGliner2RuntimeConfig:
    """``DetectionConfig`` carries the [gliner2].runtime TOML default."""

    def test_Should_DefaultRuntimeToTomlValue_When_NotProvided(self):
        # Assert the wiring (config surfaces the [gliner2].runtime TOML value)
        # without coupling to a specific tuning choice: the shipped default has
        # toggled between "fastgliner" and "pytorch" (fast_gliner ships no
        # Windows wheel). Read the expected value from the same loader the
        # config uses so this test tracks the TOML rather than a literal.
        from pii_detector.application.config import detection_policy

        expected = detection_policy._load_llm_config().get("gliner2", {}).get("runtime")
        assert DetectionConfig().gliner2_runtime == expected

    def test_Should_LeaveRuntimeNone_When_SectionAbsent(self, monkeypatch):
        from pii_detector.application.config import detection_policy

        fake_config = {
            "detection": {"llm_detection_enabled": True},
            "models": {"m": {"enabled": True, "model_id": "x", "priority": 1}},
        }
        monkeypatch.setattr(
            detection_policy, "_load_llm_config", lambda: fake_config)

        assert DetectionConfig().gliner2_runtime is None  # no raise on missing section


class TestStagedExportIsValid:
    def test_Should_BeInvalid_When_StagingMissing(self, export_dir, tmp_path):
        assert not Gliner2ModelManager._staged_export_is_valid(
            export_dir, tmp_path / "absent")

    def test_Should_BeInvalid_When_SizeDiffers(self, export_dir, tmp_path):
        staged = tmp_path / "staged"
        shutil.copytree(export_dir, staged)
        (staged / "onnx" / "model.onnx").write_bytes(b"x" * 3)

        assert not Gliner2ModelManager._staged_export_is_valid(export_dir, staged)

    def test_Should_BeValid_When_AllFilesMatch(self, export_dir, tmp_path):
        staged = tmp_path / "staged"
        shutil.copytree(export_dir, staged)

        assert Gliner2ModelManager._staged_export_is_valid(export_dir, staged)
