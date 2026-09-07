package com.app.master.service.core.dto;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class SizeStock {
    private String size;
    private Long stock;
    private Long currentStock;
}
