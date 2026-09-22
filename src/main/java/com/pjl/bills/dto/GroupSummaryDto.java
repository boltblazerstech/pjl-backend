package com.pjl.bills.dto;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;

@Data
@Builder
public class GroupSummaryDto {
    private Long id;
    private String name;
    private Long billCategoryId;
    private String billCategoryName;
    private Instant createdAt;
    private int runCount;
}
