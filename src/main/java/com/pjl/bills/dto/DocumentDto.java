package com.pjl.bills.dto;

import java.util.Map;

public record DocumentDto(
        Long id,
        String docType,
        String fileRef,
        String originalFilename,
        Map<String, Object> rawExtraction
) {
}
