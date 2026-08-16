package com.app.master.service.core.response.client;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClientCategoryResponse {
    private String name;
    private String category;
    private String image;
}
