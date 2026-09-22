package com.pjl.bills.dto;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.List;

@Data
@Builder
public class GroupDetailDto {
    private Long id;
    private String name;
    private Long billCategoryId;
    private String billCategoryName;
    private String invoiceFileRef;
    private String poFileRef;
    private String grnFileRef;
    private Instant createdAt;
    private List<GroupRunDto> pastRuns;

    @Data
    @Builder
    public static class GroupRunDto {
        private Long submissionId;
        private String status;
        private Instant submittedAt;
    }
}
