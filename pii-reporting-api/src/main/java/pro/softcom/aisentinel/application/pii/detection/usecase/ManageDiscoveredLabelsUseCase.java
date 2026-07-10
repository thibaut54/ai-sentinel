package pro.softcom.aisentinel.application.pii.detection.usecase;

import pro.softcom.aisentinel.application.pii.detection.port.in.ManageDiscoveredLabelsPort;
import pro.softcom.aisentinel.application.pii.detection.port.in.ManagePiiTypeConfigsPort;
import pro.softcom.aisentinel.application.pii.detection.port.in.ManagePiiTypeConfigsPort.CreatePiiTypeConfigCommand;
import pro.softcom.aisentinel.application.pii.detection.port.out.DiscoveredLabelStore;
import pro.softcom.aisentinel.domain.pii.detection.DiscoveredLabel;
import pro.softcom.aisentinel.domain.pii.detection.DiscoveredLabelStatus;
import pro.softcom.aisentinel.domain.pii.detection.PiiTypeConfig;

import java.util.List;
import java.util.Locale;

/**
 * Use case implementation for managing MINISTRAL discovered labels.
 * <p>
 * Promotion delegates config creation to {@link ManagePiiTypeConfigsPort} so
 * validation and persistence stay in one place, then marks the label promoted.
 */
public class ManageDiscoveredLabelsUseCase implements ManageDiscoveredLabelsPort {

    private static final String MINISTRAL_DETECTOR = "MINISTRAL";

    private final ManagePiiTypeConfigsPort managePiiTypeConfigsPort;
    private final DiscoveredLabelStore store;

    public ManageDiscoveredLabelsUseCase(ManagePiiTypeConfigsPort managePiiTypeConfigsPort,
                                         DiscoveredLabelStore store) {
        this.managePiiTypeConfigsPort = managePiiTypeConfigsPort;
        this.store = store;
    }

    @Override
    public List<DiscoveredLabel> listPending() {
        return store.findByStatus(DiscoveredLabelStatus.PENDING);
    }

    @Override
    public PiiTypeConfig promote(PromoteDiscoveredLabelCommand command) {
        CreatePiiTypeConfigCommand createCommand = new CreatePiiTypeConfigCommand(
            command.label(),
            MINISTRAL_DETECTOR,
            true,
            command.threshold(),
            command.category(),
            resolveDetectorLabel(command),
            command.countryCode(),
            command.severity(),
            command.promotedBy()
        );

        PiiTypeConfig created = managePiiTypeConfigsPort.createConfig(createCommand);
        store.markPromoted(command.label());
        return created;
    }

    @Override
    public void ignore(String label) {
        store.markIgnored(label);
    }

    private String resolveDetectorLabel(PromoteDiscoveredLabelCommand command) {
        String detectorLabel = command.detectorLabel();
        if (detectorLabel == null || detectorLabel.isBlank()) {
            return command.label().toLowerCase(Locale.ROOT);
        }
        return detectorLabel;
    }
}
