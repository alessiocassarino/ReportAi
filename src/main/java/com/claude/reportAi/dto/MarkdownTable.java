package com.claude.reportAi.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarkdownTable {
    private boolean found;
    private List<String> headers;
    private List<List<String>> rows;

    public boolean isValid() {
        return found && headers != null && !headers.isEmpty() && rows != null && !rows.isEmpty();
    }

    public int getRowCount() {
        return rows != null ? rows.size() : 0;
    }

    public int getColumnCount() {
        return headers != null ? headers.size() : 0;
    }
}
