package com.claude.reportAi.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class GenerateAttachmentsRequest {
    private List<AttachmentMetadataRequest> attachments = new ArrayList<>();
}
