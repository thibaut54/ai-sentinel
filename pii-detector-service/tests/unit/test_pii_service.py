"""
Test suite for PII gRPC Service.

This module contains comprehensive tests for the PIIDetectionServicer class,
MemoryLimitedServer class, and related functionality in pii_service.py.
"""

import os
# Add the service directory to the path for imports
import sys
import threading
from unittest.mock import Mock, patch

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

import grpc
import importlib

# Import from module with reserved keyword 'in'
pii_service = importlib.import_module('pii_detector.infrastructure.adapter.in.grpc.pii_service')
get_detector_instance = pii_service.get_detector_instance
PIIDetectionServicer = pii_service.PIIDetectionServicer
MemoryLimitedServer = pii_service.MemoryLimitedServer
serve = pii_service.serve
_detector_instance = pii_service._detector_instance
_detector_lock = pii_service._detector_lock



class TestPIIDetectionServicer:
    """Test cases for the PIIDetectionServicer class."""
    
    @pytest.fixture
    def mock_detector(self):
        """Create a mock detector instance."""
        detector = Mock()
        detector.detect_pii.return_value = [
            {
                'text': 'john@example.com',
                'type': 'EMAIL',
                'type_label': 'Email',
                'start': 0,
                'end': 16,
                'score': 0.95
            }
        ]
        detector.mask_pii.return_value = ("Masked content", [])
        # The servicer prefers detect_pii_with_stats(...) -> (entities, stats)
        # when the detector exposes it (the in-process composite path).
        # Delegate to detect_pii so the existing return_value/side_effect wiring
        # and detect_pii.assert_called_* checks keep working.
        detector.detect_pii_with_stats.side_effect = (
            lambda content, threshold, **kw: (detector.detect_pii(content, threshold, **kw), [])
        )
        return detector
    
    @pytest.fixture
    def mock_context(self):
        """Create a mock gRPC context."""
        context = Mock()
        context.peer.return_value = "ipv4:127.0.0.1:12345"
        return context
    
    @pytest.fixture
    def mock_request(self):
        """Create a mock gRPC request."""
        request = Mock()
        request.content = "Contact john@example.com for more info"
        request.threshold = 0.5
        return request
    
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.get_detector_instance')
    def test_init_default_parameters(self, mock_get_detector):
        """Test PIIDetectionServicer initialization with default parameters."""
        mock_detector = Mock()
        mock_get_detector.return_value = mock_detector
        
        with patch.object(PIIDetectionServicer, '_start_memory_monitoring'):
            servicer = PIIDetectionServicer()
        
        assert servicer.max_text_size == 1_000_000
        assert servicer.enable_memory_monitoring is True
        assert servicer.request_counter == 0
        assert servicer.gc_frequency == 10
        assert servicer.detector is mock_detector
    
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.get_detector_instance')
    def test_init_custom_parameters(self, mock_get_detector):
        """Test PIIDetectionServicer initialization with custom parameters."""
        mock_detector = Mock()
        mock_get_detector.return_value = mock_detector
        
        servicer = PIIDetectionServicer(
            max_text_size=500_000,
            enable_memory_monitoring=False
        )
        
        assert servicer.max_text_size == 500_000
        assert servicer.enable_memory_monitoring is False
        assert servicer.detector is mock_detector
    
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.get_detector_instance')
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.threading.Thread')
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.psutil.Process')
    def test_start_memory_monitoring(self, mock_process, mock_thread, mock_get_detector):
        """Test memory monitoring thread startup."""
        mock_detector = Mock()
        mock_get_detector.return_value = mock_detector
        
        servicer = PIIDetectionServicer(enable_memory_monitoring=True)
        
        mock_thread.assert_called_once()
        mock_thread.return_value.start.assert_called_once()
    
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.get_detector_instance')
    def test_process_in_chunks_small_text(self, mock_get_detector, mock_detector):
        """Test _process_in_chunks with small text that doesn't need chunking."""
        mock_get_detector.return_value = mock_detector
        
        with patch.object(PIIDetectionServicer, '_start_memory_monitoring'):
            servicer = PIIDetectionServicer()
        
        content = "Short text with john@example.com"
        threshold = 0.5
        
        result = servicer._process_in_chunks(content, threshold)
        
        mock_detector.detect_pii.assert_called_once_with(content, threshold)
        assert result == mock_detector.detect_pii.return_value
    
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.get_detector_instance')
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.gc.collect')
    def test_process_in_chunks_large_text(self, mock_gc_collect, mock_get_detector, mock_detector):
        """Test _process_in_chunks with large text that needs chunking."""
        mock_get_detector.return_value = mock_detector
        
        # Create entities with different positions for each chunk
        mock_detector.detect_pii.side_effect = [
            [{'text': 'email1', 'start': 200, 'end': 206, 'type': 'EMAIL', 'type_label': 'Email', 'score': 0.9}],
            [{'text': 'email2', 'start': 200, 'end': 206, 'type': 'EMAIL', 'type_label': 'Email', 'score': 0.9}]
        ]
        
        with patch.object(PIIDetectionServicer, '_start_memory_monitoring'):
            servicer = PIIDetectionServicer()
        
        # Create large content that will be chunked
        content = "x" * 60000  # Larger than chunk_size of 50000
        threshold = 0.5
        
        result = servicer._process_in_chunks(content, threshold)
        
        # Should be called twice for two chunks
        assert mock_detector.detect_pii.call_count == 2
        mock_gc_collect.assert_called()
        assert len(result) == 2
    
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.get_detector_instance')
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.pii_detection_pb2.PIIDetectionResponse')
    def test_detect_pii_success(self, mock_response_class, mock_get_detector, mock_detector, mock_request, mock_context):
        """Test successful DetectPII RPC call."""
        mock_get_detector.return_value = mock_detector
        mock_detector._apply_masks = Mock(return_value="masked content")
        mock_response = Mock()
        mock_response_class.return_value = mock_response
        mock_response.entities.add.return_value = Mock()
        mock_response.summary = {}
        
        with patch.object(PIIDetectionServicer, '_start_memory_monitoring'):
            servicer = PIIDetectionServicer()
        
        result = servicer.DetectPII(mock_request, mock_context)
        
        assert result is mock_response
        mock_detector.detect_pii.assert_called_once()
        mock_detector._apply_masks.assert_called_once()
    
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.get_detector_instance')
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.pii_detection_pb2.PIIDetectionResponse')
    def test_detect_pii_empty_content(self, mock_response_class, mock_get_detector, mock_detector, mock_context):
        """Test DetectPII with empty content."""
        mock_get_detector.return_value = mock_detector
        mock_response = Mock()
        mock_response_class.return_value = mock_response
        
        request = Mock()
        request.content = ""
        request.threshold = 0.5
        
        with patch.object(PIIDetectionServicer, '_start_memory_monitoring'):
            servicer = PIIDetectionServicer()
        
        result = servicer.DetectPII(request, mock_context)
        
        mock_context.set_code.assert_called_with(grpc.StatusCode.INVALID_ARGUMENT)
        mock_context.set_details.assert_called_with("Content cannot be empty")
    
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.get_detector_instance')
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.pii_detection_pb2.PIIDetectionResponse')
    def test_detect_pii_content_too_large(self, mock_response_class, mock_get_detector, mock_detector, mock_context):
        """Test DetectPII with content exceeding size limit."""
        mock_get_detector.return_value = mock_detector
        mock_response = Mock()
        mock_response_class.return_value = mock_response
        
        request = Mock()
        request.content = "x" * 1_000_001  # Exceeds default limit
        request.threshold = 0.5
        
        with patch.object(PIIDetectionServicer, '_start_memory_monitoring'):
            servicer = PIIDetectionServicer()
        
        result = servicer.DetectPII(request, mock_context)
        
        mock_context.set_code.assert_called_with(grpc.StatusCode.INVALID_ARGUMENT)
        assert "Content too large" in mock_context.set_details.call_args[0][0]
    
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.get_detector_instance')
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.pii_detection_pb2.PIIDetectionResponse')
    def test_detect_pii_large_content_chunked(self, mock_response_class, mock_get_detector, mock_detector, mock_context):
        """Test DetectPII with large content (detector handles chunking internally)."""
        mock_get_detector.return_value = mock_detector
        mock_detector._apply_masks = Mock(return_value="masked")
        mock_response = Mock()
        mock_response_class.return_value = mock_response
        mock_response.entities.add.return_value = Mock()
        mock_response.summary = {}
        
        request = Mock()
        request.content = "x" * 60000  # Large content
        request.threshold = 0.5
        
        with patch.object(PIIDetectionServicer, '_start_memory_monitoring'):
            servicer = PIIDetectionServicer()
        
        # Large content is handled by detector internally, not by _process_in_chunks
        result = servicer.DetectPII(request, mock_context)
        
        # Verify detection was called
        mock_detector.detect_pii.assert_called_once()
        assert result is mock_response
    
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.get_detector_instance')
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.pii_detection_pb2.PIIDetectionResponse')
    def test_detect_pii_exception_handling(self, mock_response_class, mock_get_detector, mock_detector, mock_request, mock_context):
        """Test DetectPII exception handling."""
        mock_get_detector.return_value = mock_detector
        mock_response = Mock()
        mock_response_class.return_value = mock_response
        
        # Make detector raise an exception
        mock_detector.detect_pii.side_effect = Exception("Test error")
        
        with patch.object(PIIDetectionServicer, '_start_memory_monitoring'):
            servicer = PIIDetectionServicer()
        
        result = servicer.DetectPII(mock_request, mock_context)
        
        mock_context.set_code.assert_called_with(grpc.StatusCode.INTERNAL)
        assert "Error processing request" in mock_context.set_details.call_args[0][0]
    
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.get_detector_instance')
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.gc.collect')
    def test_detect_pii_garbage_collection(self, mock_gc_collect, mock_get_detector, mock_detector, mock_request, mock_context):
        """Test that garbage collection is triggered periodically."""
        mock_get_detector.return_value = mock_detector
        
        with patch.object(PIIDetectionServicer, '_start_memory_monitoring'):
            servicer = PIIDetectionServicer()
        
        # Set request counter to trigger GC
        servicer.request_counter = 9  # Will become 10 after increment
        
        with patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.pii_detection_pb2.PIIDetectionResponse'):
            servicer.DetectPII(mock_request, mock_context)
        
        mock_gc_collect.assert_called()


class TestMemoryLimitedServer:
    """Test cases for the MemoryLimitedServer class."""
    
    def test_init_default_parameters(self):
        """Test MemoryLimitedServer initialization with default parameters."""
        server = MemoryLimitedServer()
        
        assert server.port == 50051
        assert server.max_workers == 5
        assert server.max_queued_requests == 100
        assert server.memory_limit_percent == 85.0
        assert server.server is None
    
    def test_init_custom_parameters(self):
        """Test MemoryLimitedServer initialization with custom parameters."""
        server = MemoryLimitedServer(
            port=8080,
            max_workers=10,
            max_queued_requests=200,
            memory_limit_percent=90.0
        )
        
        assert server.port == 8080
        assert server.max_workers == 10
        assert server.max_queued_requests == 200
        assert server.memory_limit_percent == 90.0
    
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.psutil.Process')
    def test_check_memory_within_limits(self, mock_process):
        """Test _check_memory when memory usage is within limits."""
        mock_process_instance = Mock()
        mock_process_instance.memory_percent.return_value = 70.0
        mock_process.return_value = mock_process_instance
        
        server = MemoryLimitedServer(memory_limit_percent=85.0)
        
        result = server._check_memory()
        
        assert result is True
    
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.psutil.Process')
    def test_check_memory_exceeds_limits(self, mock_process):
        """Test _check_memory when memory usage exceeds limits."""
        mock_process_instance = Mock()
        mock_process_instance.memory_percent.return_value = 90.0
        mock_process.return_value = mock_process_instance
        
        server = MemoryLimitedServer(memory_limit_percent=85.0)
        
        result = server._check_memory()
        
        assert result is False
    
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.grpc.server')
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.futures.ThreadPoolExecutor')
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.pii_detection_pb2_grpc.add_PIIDetectionServiceServicer_to_server')
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.reflection.enable_server_reflection')
    def test_serve_creates_server(self, mock_reflection, mock_add_servicer, mock_executor, mock_grpc_server):
        """Test that serve() creates and configures the gRPC server properly."""
        mock_server_instance = Mock()
        mock_grpc_server.return_value = mock_server_instance
        mock_executor_instance = Mock()
        mock_executor.return_value = mock_executor_instance
        
        server = MemoryLimitedServer(port=8080, max_workers=3)
        
        result = server.serve()
        
        # Verify server creation
        mock_grpc_server.assert_called_once_with(
            mock_executor_instance,
            options=[
                ('grpc.max_receive_message_length', 10 * 1024 * 1024),
                ('grpc.max_send_message_length', 10 * 1024 * 1024),
                ('grpc.max_concurrent_streams', 100),
                # Keepalive/connection-age tuning for long (minutes-scale)
                # inferences that send no DATA frames — see serve().
                ('grpc.http2.max_pings_without_data', 0),
                ('grpc.http2.min_time_between_pings_ms', 10_000),
                ('grpc.keepalive_permit_without_calls', 1),
                ('grpc.max_connection_age_ms', 0x7FFFFFFF),
                ('grpc.max_connection_idle_ms', 0x7FFFFFFF),
            ]
        )
        
        # Verify servicer is added
        mock_add_servicer.assert_called_once()
        
        # Verify reflection is enabled
        mock_reflection.assert_called_once()
        
        # Verify server is started
        mock_server_instance.add_insecure_port.assert_called_once_with('[::]:8080')
        mock_server_instance.start.assert_called_once()
        
        assert result is mock_server_instance
        assert server.server is mock_server_instance


class TestServeFunction:
    """Test cases for the serve() function."""
    
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.MemoryLimitedServer')
    def test_serve_function(self, mock_memory_limited_server):
        """Test the serve() function creates MemoryLimitedServer with correct parameters."""
        mock_server_instance = Mock()
        mock_server_class = Mock()
        mock_server_class.serve.return_value = "mock_grpc_server"
        mock_memory_limited_server.return_value = mock_server_class
        
        result = serve(port=8080, max_workers=10)
        
        mock_memory_limited_server.assert_called_once_with(
            port=8080,
            max_workers=10,
            max_queued_requests=100,
            memory_limit_percent=85.0
        )
        mock_server_class.serve.assert_called_once()
        assert result == "mock_grpc_server"
    
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.MemoryLimitedServer')
    def test_serve_function_default_parameters(self, mock_memory_limited_server):
        """Test the serve() function with default parameters."""
        mock_server_instance = Mock()
        mock_server_class = Mock()
        mock_memory_limited_server.return_value = mock_server_class
        
        serve()
        
        mock_memory_limited_server.assert_called_once_with(
            port=50051,
            max_workers=5,
            max_queued_requests=100,
            memory_limit_percent=85.0
        )




class TestPIIDetectionServicerAdditional:
    """Additional tests for PIIDetectionServicer to cover remaining paths."""
    
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.get_detector_instance')
    @patch('pii_detector.application.config.detection_policy._load_llm_config')
    def test_load_log_throughput_config_exception(self, mock_load_config, mock_get_detector):
        """Test _load_log_throughput_config defaults to True on exception."""
        mock_load_config.side_effect = Exception("Config load failed")
        mock_detector = Mock()
        mock_get_detector.return_value = mock_detector
        
        with patch.object(PIIDetectionServicer, '_start_memory_monitoring'):
            servicer = PIIDetectionServicer()
        
        # Should default to True
        assert servicer.log_throughput is True
    
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.get_detector_instance')
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.threading.Thread')
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.psutil.Process')
    @patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.gc.collect')
    def test_memory_monitoring_high_usage(self, mock_gc, mock_process, mock_thread, mock_get_detector):
        """Test memory monitoring triggers GC when usage is high."""
        mock_detector = Mock()
        mock_get_detector.return_value = mock_detector
        
        # Setup process mock to simulate high memory
        mock_process_instance = Mock()
        mock_memory_info = Mock()
        mock_memory_info.rss = 1000 * 1024 * 1024  # 1000 MB
        mock_process_instance.memory_info.return_value = mock_memory_info
        mock_process_instance.memory_percent.return_value = 85.0  # High usage
        mock_process.return_value = mock_process_instance
        
        # Capture the monitoring function
        monitor_func = None
        def capture_thread(target=None, daemon=None, **kwargs):
            nonlocal monitor_func
            monitor_func = target
            thread_mock = Mock()
            return thread_mock
        
        mock_thread.side_effect = capture_thread
        
        # Create servicer with monitoring
        servicer = PIIDetectionServicer(enable_memory_monitoring=True)
        
        # Simulate one iteration of monitoring (would normally loop forever)
        if monitor_func:
            try:
                # Call the monitoring function once
                import time
                with patch('pii_detector.infrastructure.adapter.in.grpc.pii_service.time.sleep', side_effect=KeyboardInterrupt):
                    monitor_func()
            except KeyboardInterrupt:
                pass  # Expected to break the loop
        
        # Verify GC was called for high memory
        assert mock_gc.called
