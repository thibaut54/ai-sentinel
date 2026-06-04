"""Dedicated test for the ``"gliner2"`` vs ``"gliner"`` routing rule.

``"gliner2"`` is a superstring of ``"gliner"``: if the substring check for
``"gliner"`` ran first, every GLiNER2 model id would be misrouted to the GLiNER
detector. This guards risk R3 (spec §4.5) and must not be removed.
"""
from __future__ import annotations

from pii_detector.application.factory.detector_factory import (
    DetectorFactory,
    create_default_factory,
)


class TestGliner2Routing:
    def test_Should_RouteToGliner2_When_ModelIdContainsGliner2(self):
        factory = DetectorFactory()
        assert factory._determine_detector_type("fastino/gliner2-large-v1") == "gliner2"
        assert factory._determine_detector_type("fastino/gliner2-pii") == "gliner2"
        assert factory._determine_detector_type("SomeOrg/GLiNER2-Custom") == "gliner2"

    def test_Should_StillRouteToGliner_When_ModelIdContainsOnlyGliner(self):
        factory = DetectorFactory()
        assert factory._determine_detector_type("nvidia/gliner-PII") == "gliner"
        assert factory._determine_detector_type("urchade/gliner_base") == "gliner"

    def test_Should_PreferMultipass_When_ModelIdContainsMultipass(self):
        factory = DetectorFactory()
        assert factory._determine_detector_type("multipass-gliner2") == "multipass-gliner"

    def test_Should_RegisterGliner2Builder_When_DefaultFactoryCreated(self):
        factory = create_default_factory()
        assert factory.is_registered("gliner2")
        # gliner stays available (ensemble, never a substitution)
        assert factory.is_registered("gliner")

    def test_Should_NotMisrouteGliner2ToGliner_EndToEnd(self):
        factory = create_default_factory()
        # Build via the routing path: a gliner2 model id must NOT yield GLiNERDetector.
        detector = factory.create("fastino/gliner2-large-v1")
        assert detector.__class__.__name__ == "Gliner2Detector"
