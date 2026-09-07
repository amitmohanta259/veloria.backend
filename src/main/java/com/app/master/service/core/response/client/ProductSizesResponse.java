package com.app.master.service.core.response.client;

import com.app.master.service.core.dto.SizeStock;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ProductSizesResponse {
    private List<SizeStock> sizes;
    private boolean inStock;
}
