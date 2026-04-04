package com.claude.reportAi.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDTO {

    private String id;

    private String email;

    private String firstName;

    private String lastName;

    private Boolean enabled;

    private List<String> roles;
}

