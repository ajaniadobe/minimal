package com.minimal.aem.assets.config;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(
    name = "Example - PDF Text Sanitizer Config",
    description = "Controls header/footer/page-number/footnote stripping from cqdam.text.txt"
)
public @interface PdfTextSanitizerConfig {

    @AttributeDefinition(
        name = "Header regexes",
        description = "Regexes for lines to strip as headers"
    )
    String[] headerRegexes() default {
        "^Confidential$",
        "^Document Title: .*$"
    };

    @AttributeDefinition(
        name = "Footer regexes",
        description = "Regexes for lines to strip as footers"
    )
    String[] footerRegexes() default {
        "^Company Internal Use Only$",
        "^Copyright .*"
    };

    @AttributeDefinition(
        name = "Page number regexes",
        description = "Regexes for common page numbering lines"
    )
    String[] pageNumberRegexes() default {
        "^Page\\s+\\d+$",
        "^Page\\s+\\d+\\s+of\\s+\\d+$",
        "^\\d+\\s*/\\s*\\d+$"
    };

    @AttributeDefinition(
        name = "Footnote regexes",
        description = "Regexes for simple footnote lines"
    )
    String[] footnoteRegexes() default {
        "^\\d+\\.\\s+.*$",
        "^\\*\\s+.*$"
    };

    @AttributeDefinition(
        name = "Minimum retained ratio",
        description = "Safety threshold. If sanitized text is too short compared to original, keep original."
    )
    double minRetainedRatio() default 0.20d;
}
