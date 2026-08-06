package com.app.master.service.core.response.admin;

import lombok.*;

@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReturnTrendPointResponse {
    private String label;
    private long count;
}
