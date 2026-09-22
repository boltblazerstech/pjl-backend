package com.pjl.core.gemini;

/**
 * Custom exception thrown when document extraction fails via the Gemini API.
 */
public class ExtractionException extends RuntimeException {

    public ExtractionException(String message) {
        super(message);
    }

    public ExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
