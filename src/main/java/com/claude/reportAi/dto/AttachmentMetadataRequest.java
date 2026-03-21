package com.claude.reportAi.dto;

import com.claude.reportAi.constant.AttachmentRole;
import lombok.Data;

@Data
public class AttachmentMetadataRequest {
    private String clientFileName;
    private AttachmentRole role;
    private String displayName;
    private String description;
    private String templateName;
    private String templateCode;
    private String templateType;
    private boolean persistAfterUse;
}
