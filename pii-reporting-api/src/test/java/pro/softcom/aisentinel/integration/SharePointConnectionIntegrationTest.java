package pro.softcom.aisentinel.integration;

import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pro.softcom.aisentinel.application.sharepoint.port.out.SharePointClient;
import pro.softcom.aisentinel.domain.sharepoint.SharePointDriveItem;
import pro.softcom.aisentinel.domain.sharepoint.SharePointSite;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("integration-test")
@EnabledIfEnvironmentVariable(named = "AZURE_TENANT_ID", matches = ".+")
class SharePointConnectionIntegrationTest {

    @TestConfiguration
    static class TestGraphClientConfig {
        @Bean
        @Primary
        Supplier<GraphServiceClient> testGraphServiceClientSupplier(
                @Value("${sharepoint.tenant-id}") String tenantId,
                @Value("${sharepoint.client-id}") String clientId,
                @Value("${sharepoint.client-secret}") String clientSecret
        ) {
            var credential = new ClientSecretCredentialBuilder()
                    .tenantId(tenantId)
                    .clientId(clientId)
                    .clientSecret(clientSecret)
                    .build();
            var client = new GraphServiceClient(credential, "https://graph.microsoft.com/.default");
            return () -> client;
        }
    }

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void registerDataSourceProps(DynamicPropertyRegistry registry) {
        postgres.start();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("pii-encryption.kek-pii-encryption-key", () -> "46TCyS1+5ie3I0zfG7RzePn7+0otW8nrwOQurxiS6z8=");
        registry.add("pii.reporting.allow-secret-reveal", () -> "true");
    }

    @Autowired
    private SharePointClient sharePointClient;

    @Value("${sharepoint.test-site-id:softcomtech.sharepoint.com:/sites/Testsite:}")
    private String testSiteId;

    @Test
    void Should_ConnectSuccessfully_When_CredentialsAreValid() {
        Boolean connected = sharePointClient.testConnection().join();

        assertThat(connected).isTrue();
    }

    @Test
    void Should_ReturnSite_When_FetchingByKnownSiteId() {
        SharePointSite site = sharePointClient.getSite(testSiteId).join();

        assertThat(site).isNotNull();
        assertThat(site.id()).isNotBlank();
        assertThat(site.name()).isNotBlank();

        System.out.println("[SHAREPOINT-IT] Found site: " + site.name() + " (" + site.id() + ")");
    }

    @Test
    void Should_ListRootDriveItems_When_SiteExists() {
        SharePointSite site = sharePointClient.getSite(testSiteId).join();
        assertThat(site).as("Test site must be accessible").isNotNull();

        System.out.println("[SHAREPOINT-IT] Listing drive items for site: " + site.name());

        List<SharePointDriveItem> items = sharePointClient.listRootDriveItems(site.id()).join();

        assertThat(items)
                .isNotEmpty()
                .allSatisfy(item -> {
            assertThat(item.id()).isNotBlank();
            assertThat(item.name()).isNotBlank();
            assertThat(item.driveId()).isNotBlank();
        });

        System.out.println("[SHAREPOINT-IT] Found " + items.size() + " root items:");
        items.forEach(item ->
            System.out.println("[SHAREPOINT-IT]   - " + (item.isFolder() ? "[DIR] " : "[FILE] ")
                + item.name() + " (" + item.mimeType() + ")")
        );
    }

    @Test
    void Should_DownloadContent_When_FileExists() {
        SharePointSite site = sharePointClient.getSite(testSiteId).join();
        assertThat(site).isNotNull();

        List<SharePointDriveItem> items = sharePointClient.listRootDriveItems(site.id()).join();
        assertThat(items).isNotEmpty();

        var file = items.stream()
            .filter(item -> !item.isFolder())
            .findFirst();

        if (file.isEmpty()) {
            System.out.println("[SHAREPOINT-IT] No file found in root drive, skipping download test");
            return;
        }

        SharePointDriveItem targetFile = file.get();
        System.out.println("[SHAREPOINT-IT] Downloading file: " + targetFile.name());

        var content = sharePointClient.downloadContent(targetFile.driveId(), targetFile.id()).join();

        assertThat(content).isNotNull();
        System.out.println("[SHAREPOINT-IT] Successfully downloaded " + targetFile.name()
            + " (" + targetFile.size() + " bytes)");
    }

    @Test
    void Should_ReturnTestsite_When_SearchingAllSites() {
        List<SharePointSite> sites = sharePointClient.searchSites("*").join();

        assertThat(sites).isNotEmpty();
        System.out.println("[SHAREPOINT-IT] Search '*' returned " + sites.size() + " sites:");
        sites.forEach(site ->
            System.out.println("[SHAREPOINT-IT]   - " + site.name() + " (" + site.id() + ")")
        );

        assertThat(sites)
            .anyMatch(site -> site.webUrl() != null
                && site.webUrl().contains("/sites/Testsite"));
    }

    @Test
    void Should_ReturnTestsite_When_SearchingByName() {
        List<SharePointSite> sites = sharePointClient.searchSites("Testsite").join();

        assertThat(sites)
            .isNotEmpty()
            .anyMatch(site -> site.webUrl() != null
                && site.webUrl().contains("/sites/Testsite"));

        System.out.println("[SHAREPOINT-IT] Search 'Testsite' returned " + sites.size() + " site(s)");
    }

    @Test
    void Should_ContainPdfFile_When_BrowsingTestsiteDocuments() {
        SharePointSite site = sharePointClient.getSite(testSiteId).join();
        assertThat(site).as("Testsite must be accessible").isNotNull();

        List<SharePointDriveItem> allItems = collectAllFilesRecursively(site.id());

        System.out.println("[SHAREPOINT-IT] All files in Testsite:");
        allItems.forEach(item ->
            System.out.println("[SHAREPOINT-IT]   - " + item.name() + " (" + item.mimeType() + ", " + item.size() + " bytes)")
        );

        assertThat(allItems)
            .as("Testsite should contain at least one PDF file")
            .anyMatch(item -> item.name() != null && item.name().toLowerCase().endsWith(".pdf"));
    }

    @Test
    void Should_ListAllDrives_When_SiteExists() {
        SharePointSite site = sharePointClient.getSite(testSiteId).join();
        assertThat(site).as("Testsite must be accessible").isNotNull();

        List<SharePointDriveItem> allDrivesItems = sharePointClient.listAllDrivesRootItems(site.id()).join();

        System.out.println("[SHAREPOINT-IT] All drives root items for site " + site.name() + ":");
        allDrivesItems.forEach(item ->
            System.out.println("[SHAREPOINT-IT]   - " + (item.isFolder() ? "[DIR] " : "[FILE] ")
                + item.name() + " (driveId=" + item.driveId() + ", mime=" + item.mimeType() + ")")
        );

        assertThat(allDrivesItems)
            .as("listAllDrivesRootItems should return items from multiple drives (Shared Documents + SitePages)")
            .isNotEmpty();
    }

    @Test
    void Should_ContainWikiPage_When_BrowsingAllDrives() {
        SharePointSite site = sharePointClient.getSite(testSiteId).join();
        assertThat(site).as("Testsite must be accessible").isNotNull();

        List<SharePointDriveItem> allDrivesItems = collectAllFilesFromAllDrives(site.id());

        System.out.println("[SHAREPOINT-IT] All files across all drives:");
        allDrivesItems.forEach(item ->
            System.out.println("[SHAREPOINT-IT]   - " + item.name() + " (driveId=" + item.driveId() + ", mime=" + item.mimeType() + ")")
        );

        assertThat(allDrivesItems)
            .as("Testsite should contain 'wiki test page' file in SitePages drive")
            .anyMatch(item -> item.name() != null
                && item.name().toLowerCase().contains("wiki test page"));
    }

    @Test
    void Should_DownloadPdfWithContent_When_PdfExistsInTestsite() throws IOException {
        SharePointSite site = sharePointClient.getSite(testSiteId).join();
        assertThat(site).isNotNull();

        List<SharePointDriveItem> allItems = collectAllFilesRecursively(site.id());
        var pdfFile = allItems.stream()
            .filter(item -> item.name() != null && item.name().toLowerCase().endsWith(".pdf"))
            .findFirst();

        assertThat(pdfFile).as("A PDF file must exist in Testsite").isPresent();

        SharePointDriveItem pdf = pdfFile.get();
        System.out.println("[SHAREPOINT-IT] Downloading PDF: " + pdf.name());

        InputStream content = sharePointClient.downloadContent(pdf.driveId(), pdf.id()).join();
        assertThat(content).isNotNull();

        byte[] bytes = content.readAllBytes();
        assertThat(bytes).hasSizeGreaterThan(0);
        assertThat(bytes[0]).as("PDF magic bytes").isEqualTo((byte) '%');

        System.out.println("[SHAREPOINT-IT] PDF downloaded: " + pdf.name() + " (" + bytes.length + " bytes)");
    }

    private List<SharePointDriveItem> collectAllFilesFromAllDrives(String siteId) {
        List<SharePointDriveItem> rootItems = sharePointClient.listAllDrivesRootItems(siteId).join();
        List<SharePointDriveItem> allFiles = new ArrayList<>();
        collectFiles(rootItems, allFiles, 0);
        return allFiles;
    }

    private List<SharePointDriveItem> collectAllFilesRecursively(String siteId) {
        List<SharePointDriveItem> rootItems = sharePointClient.listRootDriveItems(siteId).join();
        List<SharePointDriveItem> allFiles = new ArrayList<>();
        collectFiles(rootItems, allFiles, 0);
        return allFiles;
    }

    private void collectFiles(List<SharePointDriveItem> items, List<SharePointDriveItem> result, int depth) {
        if (depth > 3) return;
        for (SharePointDriveItem item : items) {
            if (item.isFolder()) {
                List<SharePointDriveItem> children =
                    sharePointClient.listChildren(item.driveId(), item.id()).join();
                collectFiles(children, result, depth + 1);
            } else {
                result.add(item);
            }
        }
    }

    @Test
    void Should_ListChildren_When_FolderExists() {
        SharePointSite site = sharePointClient.getSite(testSiteId).join();
        assertThat(site).isNotNull();

        List<SharePointDriveItem> rootItems = sharePointClient.listRootDriveItems(site.id()).join();
        assertThat(rootItems).isNotEmpty();

        var folder = rootItems.stream()
            .filter(SharePointDriveItem::isFolder)
            .findFirst();

        if (folder.isEmpty()) {
            System.out.println("[SHAREPOINT-IT] No folder found in root drive, skipping children test");
            return;
        }

        SharePointDriveItem targetFolder = folder.get();
        System.out.println("[SHAREPOINT-IT] Listing children of folder: " + targetFolder.name());

        List<SharePointDriveItem> children =
            sharePointClient.listChildren(targetFolder.driveId(), targetFolder.id()).join();

        assertThat(children).isNotNull();
        System.out.println("[SHAREPOINT-IT] Found " + children.size() + " children:");
        children.forEach(child ->
            System.out.println("[SHAREPOINT-IT]   - " + (child.isFolder() ? "[DIR] " : "[FILE] ")
                + child.name())
        );
    }
}
