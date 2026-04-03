package com.claude.reportAi.dto;

import lombok.*;

import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class VectorUploadResponse {

    private UUID id;
    private String filename;
    private String status;
}
