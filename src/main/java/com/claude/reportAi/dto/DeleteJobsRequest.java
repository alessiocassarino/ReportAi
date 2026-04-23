package com.claude.reportAi.dto;

import lombok.Data;
import java.util.List;

@Data
public class DeleteJobsRequest {

    private List<DeleteJobItem> items;

    @Data
    public static class DeleteJobItem {
        private String jobId;
        private String type;
    }
}
