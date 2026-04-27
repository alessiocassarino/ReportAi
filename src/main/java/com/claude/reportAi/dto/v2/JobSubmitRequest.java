package com.claude.reportAi.dto.v2;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class JobSubmitRequest {

    @NotBlank(message = "workflowId è obbligatorio")
    private String workflowId;

    @NotBlank(message = "sector è obbligatorio")
    @Pattern(regexp = "OIL_GAS|PHARMA|LEGAL|CIVIL|FINANCE",
             message = "sector deve essere uno tra: OIL_GAS, PHARMA, LEGAL, CIVIL, FINANCE")
    private String sector = "OIL_GAS";

    @NotBlank(message = "model è obbligatorio")
    private String model;
}
