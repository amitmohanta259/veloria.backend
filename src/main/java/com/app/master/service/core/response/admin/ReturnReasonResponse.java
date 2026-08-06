package com.app.master.service.core.response.admin;

import lombok.*;

@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReturnReasonResponse {
    private String reason;
    private long count;
    private double percentage;
}
