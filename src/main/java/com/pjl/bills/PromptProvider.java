package com.pjl.bills;

/**
 * Optional interface that a {@link VerificationStrategy} can also implement
 * to provide the Gemini extraction prompt text for each document type it processes.
 * <p>
 * The orchestrator checks whether the resolved strategy implements this interface.
 * If it does, it uses the strategy's own prompts per document type.
 * If it doesn't, it falls back to a generic extraction instruction.
 * <p>
 * This keeps {@link VerificationStrategy} minimal while still giving each strategy
 * full control over what it asks Gemini to extract from each document.
 */
public interface PromptProvider {

    /**
     * Returns the extraction prompt to use for the given document type.
     *
     * @param docType the document type (e.g. "invoice", "po", "grn")
     * @return the full prompt text to pass to {@code GeminiExtractionService}
     */
    String getExtractionPrompt(String docType);
}
