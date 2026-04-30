package com.minimal.aem.assets.service.impl;

import com.minimal.aem.assets.config.PdfTextSanitizerConfig;
import com.minimal.aem.assets.service.PdfTextSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;

import static org.junit.jupiter.api.Assertions.*;

class RegexPdfTextSanitizerTest {

    private RegexPdfTextSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        sanitizer = new RegexPdfTextSanitizer();
    }

    @Test
    void shouldSkipWhenTextIsEmpty() {
        sanitizer.activate(config(
            new String[]{"^Header$"},
            new String[]{"^Footer$"},
            new String[]{"^Page\\s+\\d+$"},
            new String[]{"^\\d+\\.\\s+.*$"},
            0.20d
        ));

        PdfTextSanitizer.Result result = sanitizer.sanitize(
            "/content/dam/test/empty.pdf",
            ""
        );

        assertFalse(result.shouldWrite());
        assertEquals("empty-text", result.reason());
        assertEquals("", result.sanitizedText());
    }

    @Test
    void shouldStripHeaderFooterAndPageNumbers() {
        sanitizer.activate(config(
            new String[]{"^ACME Quarterly Report$"},
            new String[]{"^Confidential$"},
            new String[]{"^Page\\s+\\d+\\s+of\\s+\\d+$"},
            new String[0],
            0.20d
        ));

        String original = String.join("\n",
            "ACME Quarterly Report",
            "Introduction to the body text",
            "More useful searchable content",
            "Page 1 of 12",
            "Confidential",
            "",
            "ACME Quarterly Report",
            "Second page body content",
            "Additional paragraph text",
            "Page 2 of 12",
            "Confidential"
        );

        PdfTextSanitizer.Result result = sanitizer.sanitize(
            "/content/dam/test/report.pdf",
            original
        );

        assertTrue(result.shouldWrite());
        assertEquals("sanitized", result.reason());

        String sanitized = result.sanitizedText();
        assertFalse(sanitized.contains("ACME Quarterly Report"));
        assertFalse(sanitized.contains("Confidential"));
        assertFalse(sanitized.contains("Page 1 of 12"));
        assertFalse(sanitized.contains("Page 2 of 12"));

        assertTrue(sanitized.contains("Introduction to the body text"));
        assertTrue(sanitized.contains("More useful searchable content"));
        assertTrue(sanitized.contains("Second page body content"));
        assertTrue(sanitized.contains("Additional paragraph text"));
    }

    @Test
    void shouldStripSimpleFootnoteLines() {
        sanitizer.activate(config(
            new String[0],
            new String[0],
            new String[0],
            new String[]{
                "^\\d+\\.\\s+.*$",
                "^\\*\\s+.*$"
            },
            0.20d
        ));

        String original = String.join("\n",
            "Main paragraph content that should remain searchable.",
            "More business text in the document body.",
            "1. This is a footnote that should be removed.",
            "* Another footnote-style line that should be removed."
        );

        PdfTextSanitizer.Result result = sanitizer.sanitize(
            "/content/dam/test/footnotes.pdf",
            original
        );

        assertTrue(result.shouldWrite());

        String sanitized = result.sanitizedText();
        assertTrue(sanitized.contains("Main paragraph content that should remain searchable."));
        assertTrue(sanitized.contains("More business text in the document body."));
        assertFalse(sanitized.contains("1. This is a footnote"));
        assertFalse(sanitized.contains("* Another footnote-style line"));
    }

    @Test
    void shouldSkipWhenNoChangeOccurs() {
        sanitizer.activate(config(
            new String[]{"^HeaderThatDoesNotExist$"},
            new String[]{"^FooterThatDoesNotExist$"},
            new String[]{"^Page\\s+999$"},
            new String[]{"^NeverMatches$"},
            0.20d
        ));

        String original = String.join("\n",
            "Only body text remains here.",
            "No configured regex matches these lines."
        );

        PdfTextSanitizer.Result result = sanitizer.sanitize(
            "/content/dam/test/no-change.pdf",
            original
        );

        assertFalse(result.shouldWrite());
        assertEquals("no-change", result.reason());
        assertEquals(original, result.sanitizedText());
    }

    @Test
    void shouldSkipWhenSanitizedContentFallsBelowMinimumRetainedRatio() {
        sanitizer.activate(config(
            new String[]{
                "^Header$",
                "^Keep nothing useful$",
                "^Body line 1$",
                "^Body line 2$",
                "^Body line 3$"
            },
            new String[]{"^Footer$"},
            new String[]{"^Page\\s+\\d+$"},
            new String[0],
            0.90d
        ));

        String original = String.join("\n",
            "Header",
            "Body line 1",
            "Body line 2",
            "Body line 3",
            "Page 1",
            "Footer",
            "Tiny"
        );

        PdfTextSanitizer.Result result = sanitizer.sanitize(
            "/content/dam/test/ratio.pdf",
            original
        );

        assertFalse(result.shouldWrite());
        assertEquals("below-min-retained-ratio", result.reason());
        assertEquals(original, result.sanitizedText());
    }

    @Test
    void shouldSkipWhenSanitizedTextWouldBecomeEmpty() {
        sanitizer.activate(config(
            new String[]{"^Header$"},
            new String[]{"^Footer$"},
            new String[]{"^Page\\s+\\d+$"},
            new String[]{"^\\d+\\.\\s+.*$"},
            0.01d
        ));

        String original = String.join("\n",
            "Header",
            "Page 1",
            "Footer",
            "1. Footnote"
        );

        PdfTextSanitizer.Result result = sanitizer.sanitize(
            "/content/dam/test/empty-after-sanitize.pdf",
            original
        );

        assertFalse(result.shouldWrite());
        assertEquals("sanitized-empty", result.reason());
        assertEquals(original, result.sanitizedText());
    }

    @Test
    void shouldCollapseExcessBlankLinesAfterRemoval() {
        sanitizer.activate(config(
            new String[]{"^Header$"},
            new String[]{"^Footer$"},
            new String[0],
            new String[0],
            0.20d
        ));

        String original = String.join("\n",
            "Header",
            "",
            "Body line 1",
            "",
            "",
            "Footer",
            "",
            "Body line 2"
        );

        PdfTextSanitizer.Result result = sanitizer.sanitize(
            "/content/dam/test/blank-lines.pdf",
            original
        );

        assertTrue(result.shouldWrite());

        String sanitized = result.sanitizedText();
        assertFalse(sanitized.contains("Header"));
        assertFalse(sanitized.contains("Footer"));
        assertFalse(sanitized.contains("\n\n\n"));
        assertTrue(sanitized.contains("Body line 1"));
        assertTrue(sanitized.contains("Body line 2"));
    }

    private PdfTextSanitizerConfig config(
        String[] headerRegexes,
        String[] footerRegexes,
        String[] pageNumberRegexes,
        String[] footnoteRegexes,
        double minRetainedRatio
    ) {
        return new PdfTextSanitizerConfig() {
            @Override
            public String[] headerRegexes() {
                return headerRegexes;
            }

            @Override
            public String[] footerRegexes() {
                return footerRegexes;
            }

            @Override
            public String[] pageNumberRegexes() {
                return pageNumberRegexes;
            }

            @Override
            public String[] footnoteRegexes() {
                return footnoteRegexes;
            }

            @Override
            public double minRetainedRatio() {
                return minRetainedRatio;
            }

            @Override
            public Class<? extends Annotation> annotationType() {
                return PdfTextSanitizerConfig.class;
            }
        };
    }
}