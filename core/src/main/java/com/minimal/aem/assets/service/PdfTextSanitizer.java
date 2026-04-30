package com.minimal.aem.assets.service;

public interface PdfTextSanitizer {

    Result sanitize(String assetPath, String originalText);

    final class Result {
        private final boolean shouldWrite;
        private final String sanitizedText;
        private final String reason;

        public Result(boolean shouldWrite, String sanitizedText, String reason) {
            this.shouldWrite = shouldWrite;
            this.sanitizedText = sanitizedText;
            this.reason = reason;
        }

        public boolean shouldWrite() {
            return shouldWrite;
        }

        public String sanitizedText() {
            return sanitizedText;
        }

        public String reason() {
            return reason;
        }

        public static Result skip(String reason, String originalText) {
            return new Result(false, originalText, reason);
        }

        public static Result write(String sanitizedText, String reason) {
            return new Result(true, sanitizedText, reason);
        }
    }
}
