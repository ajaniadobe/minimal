package com.minimal.aem.assets.workflow;

import com.day.cq.dam.api.Asset;
import com.day.cq.workflow.WorkflowException;
import com.day.cq.workflow.exec.WorkItem;
import com.day.cq.workflow.exec.WorkflowData;
import com.day.cq.workflow.WorkflowSession;
import com.day.cq.workflow.metadata.MetaDataMap;
import com.minimal.aem.assets.service.PdfTextSanitizer;
import com.minimal.core.testcontext.AppAemContext;
import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;
import org.apache.commons.io.IOUtils;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Proxy;
import javax.jcr.Binary;
import javax.jcr.Node;
import javax.jcr.Session;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith({AemContextExtension.class, MockitoExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class PdfTextSanitizationProcessTest {

    private final AemContext context = AppAemContext.newAemContextBuilder(ResourceResolverType.JCR_MOCK).build();

    private final ResourceResolverFactory resourceResolverFactory = mock(ResourceResolverFactory.class);
    private final PdfTextSanitizer pdfTextSanitizer = mock(PdfTextSanitizer.class);

    private PdfTextSanitizationProcess process;

    /**
     * Simple in-test mime type registry used by a custom Resource -> Asset adapter.
     * This avoids depending on full DAM Asset adaptation behavior for these unit tests.
     */
    private final Map<String, String> mimeTypesByAssetPath = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        registerNamespaceIfNeeded("minimal", "http://minimal.com/jcr/minimal/1.0");

        // Resource -> Asset adapter used by the workflow process
        context.registerAdapter(Resource.class, Asset.class, (Function<Resource, Asset>) resource -> {
            String mimeType = mimeTypesByAssetPath.get(resource.getPath());
            if (mimeType == null) {
                return null;
            }
            Asset asset = mock(Asset.class);
            when(asset.getMimeType()).thenReturn(mimeType);
            return asset;
        });

        lenient().when(resourceResolverFactory.getServiceResourceResolver(anyMap()))
            .thenAnswer(invocation -> {
                ResourceResolver underlying = context.resourceResolver();
                return (ResourceResolver) Proxy.newProxyInstance(
                    ResourceResolver.class.getClassLoader(),
                    new Class<?>[]{ResourceResolver.class},
                    (proxy, method, args) -> {
                        if ("close".equals(method.getName()) && (args == null || args.length == 0)) {
                            return null; // no-op: prevent closing the shared test resolver
                        }
                        return method.invoke(underlying, args);
                    }
                );
            });

        context.registerService(ResourceResolverFactory.class, resourceResolverFactory,
            "service.ranking", Integer.MAX_VALUE);
        context.registerService(PdfTextSanitizer.class, pdfTextSanitizer);

        process = context.registerInjectActivateService(new PdfTextSanitizationProcess());
    }

    @Test
    void shouldSanitizePdfAndWriteAuditMetadata() throws Exception {
        String assetPath = "/content/dam/test/report.pdf";
        createAssetWithExtractedText(assetPath, "application/pdf", "Header\nUseful body text\nFooter");

        when(pdfTextSanitizer.sanitize(eq(assetPath), eq("Header\nUseful body text\nFooter")))
            .thenReturn(PdfTextSanitizer.Result.write("Useful body text", "sanitized"));

        process.execute(
            mockWorkItem(assetPath),
            mock(WorkflowSession.class),
            mock(MetaDataMap.class)
        );

        verify(resourceResolverFactory, times(1)).getServiceResourceResolver(anyMap());
        verify(pdfTextSanitizer, times(1)).sanitize(assetPath, "Header\nUseful body text\nFooter");

        assertEquals("Useful body text", readExtractedText(assetPath));

        ModifiableValueMap metadata = getMetadata(assetPath);
        assertNotNull(metadata);
        assertEquals(true, metadata.get("example:pdfTextSanitized", Boolean.class));
        assertEquals("sanitized", metadata.get("example:pdfTextSanitizerReason", String.class));
        assertEquals(Integer.valueOf(30), metadata.get("example:pdfOriginalChars", Integer.class));
        assertEquals(Integer.valueOf(16), metadata.get("example:pdfSanitizedChars", Integer.class));
        assertNotNull(metadata.get("example:pdfTextSanitizedAt", String.class));
        assertEquals("v1", metadata.get("example:pdfTextSanitizerVersion", String.class));
    }

    @Test
    void shouldSkipNonPdfAssets() throws Exception {
        String assetPath = "/content/dam/test/image.png";
        createAssetWithExtractedText(assetPath, "image/png", "some extracted text that should remain unchanged");

        process.execute(
            mockWorkItem(assetPath),
            mock(WorkflowSession.class),
            mock(MetaDataMap.class)
        );

        verify(resourceResolverFactory, times(1)).getServiceResourceResolver(anyMap());
        verifyNoInteractions(pdfTextSanitizer);

        assertEquals("some extracted text that should remain unchanged", readExtractedText(assetPath));

        ModifiableValueMap metadata = getMetadata(assetPath);
        assertNull(metadata.get("example:pdfTextSanitized"));
        assertNull(metadata.get("example:pdfTextSanitizerReason"));
    }

    @Test
    void shouldSkipWhenTextRenditionIsMissing() throws Exception {
        String assetPath = "/content/dam/test/missing-text-rendition.pdf";
        createAssetWithoutExtractedText(assetPath, "application/pdf");

        process.execute(
            mockWorkItem(assetPath),
            mock(WorkflowSession.class),
            mock(MetaDataMap.class)
        );

        verify(resourceResolverFactory, times(1)).getServiceResourceResolver(anyMap());
        verifyNoInteractions(pdfTextSanitizer);

        ModifiableValueMap metadata = getMetadata(assetPath);
        assertNull(metadata.get("example:pdfTextSanitized"));
        assertNull(metadata.get("example:pdfTextSanitizerReason"));
    }

    @Test
    void shouldPreserveOriginalTextWhenSanitizerReturnsSkip() throws Exception {
        String assetPath = "/content/dam/test/skip.pdf";
        createAssetWithExtractedText(assetPath, "application/pdf", "Original searchable body");

        when(pdfTextSanitizer.sanitize(eq(assetPath), eq("Original searchable body")))
            .thenReturn(PdfTextSanitizer.Result.skip("no-change", "Original searchable body"));

        process.execute(
            mockWorkItem(assetPath),
            mock(WorkflowSession.class),
            mock(MetaDataMap.class)
        );

        verify(pdfTextSanitizer, times(1)).sanitize(assetPath, "Original searchable body");
        assertEquals("Original searchable body", readExtractedText(assetPath));

        ModifiableValueMap metadata = getMetadata(assetPath);
        assertEquals(false, metadata.get("example:pdfTextSanitized", Boolean.class));
        assertEquals("no-change", metadata.get("example:pdfTextSanitizerReason", String.class));
    }

    @Test
    void shouldWrapLoginExceptionInWorkflowException() throws Exception {
        reset(resourceResolverFactory);
        when(resourceResolverFactory.getServiceResourceResolver(anyMap()))
            .thenThrow(new LoginException("service user failure"));

        context.registerService(ResourceResolverFactory.class, resourceResolverFactory,
            "service.ranking", Integer.MAX_VALUE);
        process = context.registerInjectActivateService(new PdfTextSanitizationProcess());

        String assetPath = "/content/dam/test/failure.pdf";

        WorkflowException ex = assertThrows(
            WorkflowException.class,
            () -> process.execute(
                mockWorkItem(assetPath),
                mock(WorkflowSession.class),
                mock(MetaDataMap.class)
            )
        );

        assertTrue(ex.getMessage().contains("Unable to obtain service resolver"));
        verifyNoInteractions(pdfTextSanitizer);
    }

    @Test
    void shouldIgnoreNonJcrPayloadType() throws Exception {
        WorkItem workItem = mock(WorkItem.class);
        WorkflowData workflowData = mock(WorkflowData.class);

        when(workItem.getWorkflowData()).thenReturn(workflowData);
        when(workflowData.getPayloadType()).thenReturn("PACKAGE");
        when(workflowData.getPayload()).thenReturn("/content/dam/test/ignored.pdf");

        process.execute(
            workItem,
            mock(WorkflowSession.class),
            mock(MetaDataMap.class)
        );

        verifyNoInteractions(pdfTextSanitizer);
        verify(resourceResolverFactory, never()).getServiceResourceResolver(anyMap());
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private WorkItem mockWorkItem(String assetPath) {
        WorkItem workItem = mock(WorkItem.class);
        WorkflowData workflowData = mock(WorkflowData.class);

        when(workItem.getWorkflowData()).thenReturn(workflowData);
        when(workflowData.getPayloadType()).thenReturn("JCR_PATH");
        when(workflowData.getPayload()).thenReturn(assetPath);

        return workItem;
    }

    private void createAssetWithExtractedText(String assetPath, String mimeType, String extractedText) throws Exception {
        createBaseAsset(assetPath, mimeType);

        context.create().resource(
            assetPath + "/jcr:content/renditions/cqdam.text.txt",
            "jcr:primaryType", "nt:file"
        );
        context.create().resource(
            assetPath + "/jcr:content/renditions/cqdam.text.txt/jcr:content",
            "jcr:primaryType", "nt:resource",
            "jcr:mimeType", "text/plain"
        );

        writeBinary(
            assetPath + "/jcr:content/renditions/cqdam.text.txt/jcr:content",
            "jcr:data",
            extractedText
        );
    }

    private void createAssetWithoutExtractedText(String assetPath, String mimeType) {
        createBaseAsset(assetPath, mimeType);
    }

    private void createBaseAsset(String assetPath, String mimeType) {
        mimeTypesByAssetPath.put(assetPath, mimeType);

        context.create().resource(
            assetPath,
            "jcr:primaryType", "dam:Asset"
        );
        context.create().resource(
            assetPath + "/jcr:content",
            "jcr:primaryType", "dam:AssetContent"
        );
        context.create().resource(
            assetPath + "/jcr:content/metadata",
            "jcr:primaryType", "nt:unstructured"
        );
        context.create().resource(
            assetPath + "/jcr:content/renditions",
            "jcr:primaryType", "sling:OrderedFolder"
        );
    }

    private String readExtractedText(String assetPath) throws Exception {
        Resource resource = context.resourceResolver().getResource(
            assetPath + "/jcr:content/renditions/cqdam.text.txt/jcr:content"
        );
        assertNotNull(resource, "Expected cqdam.text.txt/jcr:content to exist");

        Node node = resource.adaptTo(Node.class);
        assertNotNull(node);

        try (InputStream is = node.getProperty("jcr:data").getBinary().getStream()) {
            return IOUtils.toString(is, StandardCharsets.UTF_8);
        }
    }

    private void writeBinary(String resourcePath, String propertyName, String value) throws Exception {
        Resource resource = context.resourceResolver().getResource(resourcePath);
        assertNotNull(resource, "Expected resource to exist: " + resourcePath);

        Node node = resource.adaptTo(Node.class);
        assertNotNull(node, "Expected resource to adapt to JCR Node");

        Session session = context.resourceResolver().adaptTo(Session.class);
        assertNotNull(session, "Expected resolver to adapt to JCR Session");

        try (InputStream is = new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8))) {
            Binary binary = session.getValueFactory().createBinary(is);
            node.setProperty(propertyName, binary);
            session.save();
        }
    }

    private ModifiableValueMap getMetadata(String assetPath) {
        Resource metadata = context.resourceResolver().getResource(assetPath + "/jcr:content/metadata");
        assertNotNull(metadata, "Expected metadata node to exist");
        return metadata.adaptTo(ModifiableValueMap.class);
    }

    private void registerNamespaceIfNeeded(String prefix, String uri) throws Exception {
        Session session = context.resourceResolver().adaptTo(Session.class);
        assertNotNull(session, "Expected resolver to adapt to JCR Session");

        try {
            session.getWorkspace().getNamespaceRegistry().registerNamespace(prefix, uri);
        } catch (javax.jcr.NamespaceException ignored) {
            // already registered
        }
    }
}