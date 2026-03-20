package com.claude.reportAi.dto;

import lombok.Data;

import java.util.List;

@Data
public class ReportTableResponse {
    private List<String> headers;
    private List<List<String>> rows;
}
