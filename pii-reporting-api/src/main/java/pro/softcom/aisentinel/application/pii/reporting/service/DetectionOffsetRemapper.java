package pro.softcom.aisentinel.application.pii.reporting.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pro.softcom.aisentinel.domain.confluence.extraction.OffsetMapping;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.DiscardedSensitiveData;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.SensitiveData;

import java.util.ArrayList;
import java.util.List;

/**
 * Remaps detector positions from the "analysis" text space to the "context" text space.
 *
 * <p>Business purpose: tabular extraction runs the detector on a text where each value is prefixed
 * by its column header. The positions returned by the detector therefore index the analysis text,
 * while the report stores the raw context text. This pure function rewrites each detection's
 * {@code position}/{@code end} into the context space using the {@link OffsetMapping}.
 *
 * <p>Detections whose range cannot be remapped — they landed on a synthetic label ({@code Header : })
 * or a separator ({@code |}), i.e. outside any value segment — are dropped and logged. For the
 * identity mapping (non-tabular content) the detection is returned unchanged.
 */
public final class DetectionOffsetRemapper {

    private static final Logger log = LoggerFactory.getLogger(DetectionOffsetRemapper.class);

    private DetectionOffsetRemapper() {
        throw new AssertionError("DetectionOffsetRemapper is a utility class and should not be instantiated");
    }

    /**
     * Returns a detection whose sensitive-data positions are expressed in the context space.
     *
     * @param detection the detection produced on the analysis text (positions in analysis space)
     * @param mapping the analysis→context offset correspondence
     * @return the same detection for the identity mapping or a null/empty input, otherwise a new
     *         detection with remapped positions and unmappable entities dropped
     */
    public static ContentPiiDetection remap(ContentPiiDetection detection, OffsetMapping mapping) {
        if (detection == null || mapping == null || mapping.isIdentity()) {
            return detection;
        }

        List<SensitiveData> remappedFound = remapSensitiveData(detection.sensitiveDataFound(), mapping);
        List<DiscardedSensitiveData> remappedDiscarded = remapDiscarded(detection.discardedByJudge(), mapping);

        return ContentPiiDetection.builder()
            .pageId(detection.pageId())
            .pageTitle(detection.pageTitle())
            .spaceKey(detection.spaceKey())
            .analysisDate(detection.analysisDate())
            .sensitiveDataFound(remappedFound)
            .statistics(detection.statistics())
            .discardedByJudge(remappedDiscarded)
            .detectorRunStats(detection.detectorRunStats())
            .build();
    }

    private static List<SensitiveData> remapSensitiveData(List<SensitiveData> entities, OffsetMapping mapping) {
        if (entities == null || entities.isEmpty()) {
            return entities;
        }
        List<SensitiveData> result = new ArrayList<>(entities.size());
        for (SensitiveData entity : entities) {
            mapping.remap(entity.position(), entity.end())
                .map(span -> withPositions(entity, span.start(), span.end()))
                .ifPresentOrElse(result::add, () -> logDropped(entity));
        }
        return result;
    }

    private static List<DiscardedSensitiveData> remapDiscarded(List<DiscardedSensitiveData> discarded,
                                                               OffsetMapping mapping) {
        if (discarded == null || discarded.isEmpty()) {
            return discarded;
        }
        List<DiscardedSensitiveData> result = new ArrayList<>(discarded.size());
        for (DiscardedSensitiveData item : discarded) {
            SensitiveData data = item.data();
            mapping.remap(data.position(), data.end())
                .map(span -> new DiscardedSensitiveData(
                    withPositions(data, span.start(), span.end()),
                    item.judgeVerdict(), item.judgeConfidence(), item.judgeReason()))
                .ifPresent(result::add);
        }
        return result;
    }

    private static SensitiveData withPositions(SensitiveData entity, int start, int end) {
        return new SensitiveData(
            entity.type(), entity.typeLabel(), entity.value(), entity.context(),
            start, end, entity.score(), entity.selector(), entity.source(), entity.judgeStatus());
    }

    private static void logDropped(SensitiveData entity) {
        log.debug("[OFFSET_REMAP][DROP] detection on synthetic label/separator discarded: type={} analysis=[{},{}]",
            entity.type(), entity.position(), entity.end());
    }
}
