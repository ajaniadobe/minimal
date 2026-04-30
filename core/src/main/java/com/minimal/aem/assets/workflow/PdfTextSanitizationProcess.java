package com.minimal.aem.assets.workflow;

import com.day.cq.dam.api.Asset;
import com.day.cq.workflow.WorkflowException;
import com.day.cq.workflow.exec.WorkItem;
import com.day.cq.workflow.exec.WorkflowData;
import com.day.cq.workflow.exec.WorkflowProcess;
import com.day.cq.workflow.metadata.MetaDataMap;
import com.minimal.aem.assets.service.PdfTextSanitizer;
import org.apache.commons.io.IOUtils;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Binary;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.ValueFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

@Component(
    service = WorkflowProcess.class,
    property = {
        "process.label=Sanitize Extracted PDF Text"
    }
)
public class PdfTextSanitizationProcess implements WorkflowProcess {

    private static final Logger LOG = LoggerFactory.getLogger(PdfTextSanitizationProcess.class);

    private static final String SUBSERVICE = "pdf-text-sanitizer-service";
    private static final String PDF_MIME = "application/pdf";
    private static final String TEXT_RENDITION_REL_PATH = "jcr:content/renditions/cqdam.text.txt";
    private static final String METADATA_REL_PATH = "jcr:content/metadata";

    @Reference
    private ResourceResolverFactory resourceResolverFactory;

    @Reference
    private PdfTextSanitizer pdfTextSanitizer;

    @Override
    public void execute(WorkItem workItem, com.day.cq.workflow.WorkflowSession workflowSession, MetaDataMap args)
            throws WorkflowException {

        WorkflowData workflowData = workItem.getWorkflowData();
        String payloadType = workflowData.getPayloadType();

        if (!"JCR_PATH".equals(payloadType)) {
            LOG.debug("Skipping workflow payload because type is not JCR_PATH: {}", payloadType);
            return;
        }

        String assetPath = String.valueOf(workflowData.getPayload());

        Map<String, Object> authInfo = Collections.singletonMap(
            ResourceResolverFactory.SUBSERVICE,
            (Object) SUBSERVICE
        );

        try (ResourceResolver resolver = resourceResolverFactory.getServiceResourceResolver(authInfo)) {
            processAsset(assetPath, resolver);
        } catch (LoginException e) {
            throw new WorkflowException("Unable to obtain service resolver", e);
        } catch (PersistenceException e) {
            throw new WorkflowException("Unable to persist changes", e);
        }
    }

    private void processAsset(String assetPath, ResourceResolver resolver) throws WorkflowException, PersistenceException {
        Resource assetResource = resolver.getResource(assetPath);
        if (assetResource == null) {
            LOG.warn("Asset not found: {}", assetPath);
            return;
        }

        Asset asset = assetResource.adaptTo(Asset.class);
        if (asset == null) {
            LOG.warn("Payload is not a DAM asset: {}", assetPath);
            return;
        }

        String mimeType = asset.getMimeType();
        if (!PDF_MIME.equalsIgnoreCase(mimeType)) {
            LOG.debug("Skipping non-PDF asset {} with mimeType={}", assetPath, mimeType);
            return;
        }

        Resource textRendition = assetResource.getChild(TEXT_RENDITION_REL_PATH);
        if (textRendition == null) {
            LOG.warn("No cqdam.text.txt rendition found for asset {}", assetPath);
            return;
        }

        String originalText = readTextRendition(textRendition);
        if (originalText == null || originalText.isBlank()) {
            LOG.debug("Empty extracted text for asset {}", assetPath);
            return;
        }

        PdfTextSanitizer.Result result = pdfTextSanitizer.sanitize(assetPath, originalText);

        if (!result.shouldWrite()) {
            LOG.info("Sanitizer chose not to overwrite extracted text for asset {}. reason={}",
                assetPath, result.reason());
            writeAuditMetadata(assetResource, resolver, false, result.reason(), originalText.length(), originalText.length());
            resolver.commit();
            return;
        }

        overwriteTextRendition(textRendition, result.sanitizedText(), resolver);
        writeAuditMetadata(assetResource, resolver, true, result.reason(),
            originalText.length(), result.sanitizedText().length());

        resolver.commit();

        LOG.info("Sanitized extracted text for asset {}. originalChars={}, sanitizedChars={}",
            assetPath, originalText.length(), result.sanitizedText().length());
    }

    private String readTextRendition(Resource textRenditionResource) throws WorkflowException {
        Node fileNode = textRenditionResource.adaptTo(Node.class);
        if (fileNode == null) {
            throw new WorkflowException("Unable to adapt text rendition to JCR node");
        }

        try {
            Node jcrContent = fileNode.getNode("jcr:content");
            try (InputStream is = jcrContent.getProperty("jcr:data").getBinary().getStream()) {
                return IOUtils.toString(is, StandardCharsets.UTF_8);
            }
        } catch (RepositoryException | java.io.IOException e) {
            throw new WorkflowException("Failed reading cqdam.text.txt rendition", e);
        }
    }

    private void overwriteTextRendition(Resource textRenditionResource,
                                        String sanitizedText,
                                        ResourceResolver resolver) throws WorkflowException {
        Node fileNode = textRenditionResource.adaptTo(Node.class);
        if (fileNode == null) {
            throw new WorkflowException("Unable to adapt text rendition file node");
        }

        try {
            Node jcrContent = fileNode.getNode("jcr:content");
            Session session = resolver.adaptTo(Session.class);
            if (session == null) {
                throw new WorkflowException("Unable to adapt resolver to JCR session");
            }

            ValueFactory valueFactory = session.getValueFactory();
            byte[] bytes = sanitizedText.getBytes(StandardCharsets.UTF_8);

            try (InputStream is = new ByteArrayInputStream(bytes)) {
                Binary binary = valueFactory.createBinary(is);
                jcrContent.setProperty("jcr:data", binary);
                jcrContent.setProperty("jcr:mimeType", "text/plain");
                jcrContent.setProperty("jcr:lastModified", java.util.Calendar.getInstance());
            }
        } catch (RepositoryException | java.io.IOException e) {
            throw new WorkflowException("Failed overwriting cqdam.text.txt rendition", e);
        }
    }

    private void writeAuditMetadata(Resource assetResource,
                                    ResourceResolver resolver,
                                    boolean sanitized,
                                    String reason,
                                    int originalChars,
                                    int sanitizedChars) {
        Resource metadataResource = assetResource.getChild(METADATA_REL_PATH);
        if (metadataResource == null) {
            LOG.debug("No metadata node found for {}", assetResource.getPath());
            return;
        }

        ModifiableValueMap mvm = metadataResource.adaptTo(ModifiableValueMap.class);
        if (mvm == null) {
            LOG.debug("Metadata node not modifiable for {}", assetResource.getPath());
            return;
        }

        mvm.put("example:pdfTextSanitized", sanitized);
        mvm.put("example:pdfTextSanitizedAt", Instant.now().toString());
        mvm.put("example:pdfTextSanitizerReason", reason);
        mvm.put("example:pdfOriginalChars", originalChars);
        mvm.put("example:pdfSanitizedChars", sanitizedChars);
        mvm.put("example:pdfTextSanitizerVersion", "v1");
    }
}
