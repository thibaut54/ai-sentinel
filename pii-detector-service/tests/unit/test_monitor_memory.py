"""
Unit tests for the standalone memory-monitoring utility
:mod:`pii_detector.utils.monitor_memory`.

The module talks to the OS only through :mod:`psutil`, so every branch is
exercised here by mocking ``psutil.Process`` / ``psutil.process_iter``. No real
process is inspected and no file is written outside ``tmp_path``. The tests
cover the pure formatting/alerting logic (uptime, output line, alert level),
the single monitor iteration state machine and the ``find_process_by_name``
lookup.
"""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import psutil
import pytest

from pii_detector.utils.monitor_memory import (
    MemoryMonitor,
    find_process_by_name,
)


def _make_process(*, name: str = "python", rss_mb: float = 100.0,
                  mem_percent: float = 10.0, cpu_percent: float = 5.0) -> MagicMock:
    """Build a psutil.Process-like mock returning fixed stats."""
    process = MagicMock()
    process.name.return_value = name
    process.memory_info.return_value = MagicMock(rss=int(rss_mb * 1024 * 1024))
    process.memory_percent.return_value = mem_percent
    process.cpu_percent.return_value = cpu_percent
    return process


class TestMemoryMonitorInit:
    def test_Should_RecordInitialMemory_When_ProcessExists(self) -> None:
        process = _make_process(rss_mb=256.0)
        with patch.object(psutil, "Process", return_value=process):
            monitor = MemoryMonitor(pid=1234)

        assert monitor.pid == 1234
        assert monitor.initial_memory == pytest.approx(256.0)
        assert monitor.warning_threshold == 80.0
        assert monitor.critical_threshold == 90.0

    def test_Should_ExitProcess_When_PidNotFound(self) -> None:
        with patch.object(psutil, "Process", side_effect=psutil.NoSuchProcess(1234)):
            with pytest.raises(SystemExit):
                MemoryMonitor(pid=1234)


class TestGetMemoryStats:
    def test_Should_ReturnStatsAndTrackPeak_When_ProcessAlive(self) -> None:
        process = _make_process(rss_mb=512.0, mem_percent=42.0, cpu_percent=7.5)
        with patch.object(psutil, "Process", return_value=process):
            monitor = MemoryMonitor(pid=1)
            stats = monitor.get_memory_stats()

        assert stats is not None
        assert stats["memory_mb"] == pytest.approx(512.0)
        assert stats["memory_percent"] == 42.0
        assert stats["cpu_percent"] == 7.5
        assert stats["peak_memory_mb"] == pytest.approx(512.0)
        assert monitor.peak_memory == pytest.approx(512.0)

    def test_Should_ReturnNone_When_ProcessTerminatedMidStats(self) -> None:
        process = _make_process()
        with patch.object(psutil, "Process", return_value=process):
            monitor = MemoryMonitor(pid=1)
            # Process dies between init and the stats read.
            process.memory_info.side_effect = psutil.NoSuchProcess(1)
            stats = monitor.get_memory_stats()

        assert stats is None


class TestFormatUptime:
    @pytest.mark.parametrize(
        "seconds, expected",
        [
            (0, "00:00:00"),
            (59, "00:00:59"),
            (61, "00:01:01"),
            (3661, "01:01:01"),
            (90061, "25:01:01"),
        ],
    )
    def test_Should_RenderHmsZeroPadded_When_GivenSeconds(
        self, seconds: int, expected: str
    ) -> None:
        process = _make_process()
        with patch.object(psutil, "Process", return_value=process):
            monitor = MemoryMonitor(pid=1)

        assert monitor.format_uptime(seconds) == expected


class TestAlertLevel:
    @pytest.mark.parametrize(
        "mem_percent, expected",
        [
            (50.0, ""),
            (80.0, " [WARNING]"),
            (85.0, " [WARNING]"),
            (90.0, " [CRITICAL]"),
            (95.0, " [CRITICAL]"),
        ],
    )
    def test_Should_PickAlertLevel_When_MemoryCrossesThresholds(
        self, mem_percent: float, expected: str
    ) -> None:
        process = _make_process()
        with patch.object(psutil, "Process", return_value=process):
            monitor = MemoryMonitor(pid=1)

        assert monitor._get_alert_level({"memory_percent": mem_percent}) == expected

    def test_Should_PrintCriticalNotice_When_AboveCriticalThreshold(
        self, capsys: pytest.CaptureFixture[str]
    ) -> None:
        process = _make_process()
        with patch.object(psutil, "Process", return_value=process):
            monitor = MemoryMonitor(pid=1)
            monitor._check_critical_memory({"memory_percent": 97.0})

        out = capsys.readouterr().out
        assert "CRITICAL" in out

    def test_Should_StaySilent_When_BelowCriticalThreshold(
        self, capsys: pytest.CaptureFixture[str]
    ) -> None:
        process = _make_process()
        with patch.object(psutil, "Process", return_value=process):
            monitor = MemoryMonitor(pid=1)
            monitor._check_critical_memory({"memory_percent": 50.0})

        assert capsys.readouterr().out == ""


class TestFormatMonitorOutput:
    def test_Should_RenderAllFieldsAndAlert_When_Critical(self) -> None:
        process = _make_process()
        with patch.object(psutil, "Process", return_value=process):
            monitor = MemoryMonitor(pid=1)

        stats = {
            "memory_mb": 1024.0,
            "memory_percent": 95.0,
            "cpu_percent": 12.5,
            "peak_memory_mb": 2048.0,
            "uptime_seconds": 3661,
        }
        line = monitor._format_monitor_output(stats)

        assert "Memory: 1024.00 MB (95.0%)" in line
        assert "CPU: 12.5%" in line
        assert "Peak: 2048.00 MB" in line
        assert "Uptime: 01:01:01" in line
        assert "[CRITICAL]" in line


class TestWriteMonitorOutput:
    def test_Should_WriteToConsoleAndLog_When_LogHandleProvided(
        self, capsys: pytest.CaptureFixture[str]
    ) -> None:
        process = _make_process()
        with patch.object(psutil, "Process", return_value=process):
            monitor = MemoryMonitor(pid=1)

        log_handle = MagicMock()
        monitor._write_monitor_output("line", log_handle)

        assert "line" in capsys.readouterr().out
        log_handle.write.assert_called_once_with("line\n")
        log_handle.flush.assert_called_once()

    def test_Should_OnlyPrint_When_NoLogHandle(
        self, capsys: pytest.CaptureFixture[str]
    ) -> None:
        process = _make_process()
        with patch.object(psutil, "Process", return_value=process):
            monitor = MemoryMonitor(pid=1)
            monitor._write_monitor_output("line", None)

        assert "line" in capsys.readouterr().out


class TestOpenLogFile:
    def test_Should_ReturnNone_When_NoPathGiven(self) -> None:
        process = _make_process()
        with patch.object(psutil, "Process", return_value=process):
            monitor = MemoryMonitor(pid=1)

        assert monitor._open_log_file(None) is None

    def test_Should_OpenAndWriteHeader_When_PathGiven(self, tmp_path) -> None:
        process = _make_process()
        log_path = tmp_path / "monitor.log"
        with patch.object(psutil, "Process", return_value=process):
            monitor = MemoryMonitor(pid=1)
            handle = monitor._open_log_file(str(log_path))

        assert handle is not None
        handle.close()
        content = log_path.read_text()
        assert "Memory monitoring started" in content

    def test_Should_ReturnNoneAndWarn_When_OpenFails(
        self, capsys: pytest.CaptureFixture[str]
    ) -> None:
        process = _make_process()
        with patch.object(psutil, "Process", return_value=process):
            monitor = MemoryMonitor(pid=1)
            with patch(
                "pii_detector.utils.monitor_memory.open",
                side_effect=OSError("denied"),
            ):
                handle = monitor._open_log_file("/nope/monitor.log")

        assert handle is None
        assert "Could not open log file" in capsys.readouterr().out

    def test_Should_CloseHandle_When_HandleOpen(self) -> None:
        process = _make_process()
        with patch.object(psutil, "Process", return_value=process):
            monitor = MemoryMonitor(pid=1)

        handle = MagicMock()
        monitor._close_log_file(handle)
        handle.close.assert_called_once()

    def test_Should_NoOp_When_HandleIsNone(self) -> None:
        process = _make_process()
        with patch.object(psutil, "Process", return_value=process):
            monitor = MemoryMonitor(pid=1)
        # No exception expected.
        monitor._close_log_file(None)


class TestMonitorIteration:
    def test_Should_StopAndReportTermination_When_StatsAreNone(
        self, capsys: pytest.CaptureFixture[str]
    ) -> None:
        process = _make_process()
        with patch.object(psutil, "Process", return_value=process):
            monitor = MemoryMonitor(pid=1)
            with patch.object(monitor, "get_memory_stats", return_value=None):
                keep_going = monitor._monitor_iteration(interval=1, log_handle=None)

        assert keep_going is False
        assert "Process terminated" in capsys.readouterr().out

    def test_Should_ContinueAndWriteOutput_When_StatsAvailable(self) -> None:
        process = _make_process()
        stats = {
            "memory_mb": 100.0,
            "memory_percent": 10.0,
            "cpu_percent": 1.0,
            "peak_memory_mb": 100.0,
            "uptime_seconds": 5,
        }
        with patch.object(psutil, "Process", return_value=process):
            monitor = MemoryMonitor(pid=1)
            with patch.object(monitor, "get_memory_stats", return_value=stats), \
                    patch("time.sleep") as mock_sleep:
                keep_going = monitor._monitor_iteration(interval=3, log_handle=None)

        assert keep_going is True
        mock_sleep.assert_called_once_with(3)


class TestMonitorLoop:
    def test_Should_StopGracefully_When_KeyboardInterrupt(
        self, capsys: pytest.CaptureFixture[str]
    ) -> None:
        process = _make_process()
        with patch.object(psutil, "Process", return_value=process):
            monitor = MemoryMonitor(pid=1)
            with patch.object(
                monitor, "_monitor_iteration", side_effect=KeyboardInterrupt
            ):
                monitor.monitor(interval=1)

        assert "user interrupt" in capsys.readouterr().out

    def test_Should_StopGracefully_When_UnexpectedError(
        self, capsys: pytest.CaptureFixture[str]
    ) -> None:
        process = _make_process()
        with patch.object(psutil, "Process", return_value=process):
            monitor = MemoryMonitor(pid=1)
            with patch.object(
                monitor, "_monitor_iteration", side_effect=RuntimeError("boom")
            ):
                monitor.monitor(interval=1)

        assert "error: boom" in capsys.readouterr().out

    def test_Should_ExitLoop_When_IterationSignalsStop(self) -> None:
        process = _make_process()
        with patch.object(psutil, "Process", return_value=process):
            monitor = MemoryMonitor(pid=1)
            with patch.object(monitor, "_monitor_iteration", return_value=False):
                # Returns immediately because the single iteration asks to stop.
                monitor.monitor(interval=1)


class TestFindProcessByName:
    def test_Should_MatchOnProcessName_When_NameSubstringFound(self) -> None:
        proc = MagicMock()
        proc.info = {"pid": 42, "name": "python3.13", "cmdline": []}
        with patch.object(psutil, "process_iter", return_value=[proc]):
            assert find_process_by_name("python") == 42

    def test_Should_MatchOnCmdline_When_NameOnlyInCmdline(self) -> None:
        proc = MagicMock()
        proc.info = {
            "pid": 7,
            "name": "bash",
            "cmdline": ["python", "server.py"],
        }
        with patch.object(psutil, "process_iter", return_value=[proc]):
            assert find_process_by_name("server.py") == 7

    def test_Should_SkipInaccessibleProcesses_And_ReturnNone_When_NoMatch(self) -> None:
        dead = MagicMock()
        type(dead).info = property(
            lambda self: (_ for _ in ()).throw(psutil.AccessDenied(1))
        )
        other = MagicMock()
        other.info = {"pid": 9, "name": "nginx", "cmdline": []}
        with patch.object(psutil, "process_iter", return_value=[dead, other]):
            assert find_process_by_name("python") is None
