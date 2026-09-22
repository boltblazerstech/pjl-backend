package com.pjl.bills.dto;

import java.util.List;

public record BillCategoryDto(
        Long id,
        String name,
        List<String> requiredDocumentTypes
) {
}
