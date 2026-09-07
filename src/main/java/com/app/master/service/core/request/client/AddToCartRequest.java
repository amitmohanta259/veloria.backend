package com.app.master.service.core.request.client;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class AddToCartRequest {
    private UUID productUuid;
    private int quantity = 1;
    private String size;
}
