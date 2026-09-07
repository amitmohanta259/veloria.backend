package com.app.master.service.service.client;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.client.ClientCategoryResponse;
import com.app.master.service.core.response.client.ClientProductDetailResponse;
import com.app.master.service.core.response.client.NewArrivalProductResponse;
import com.app.master.service.core.response.client.ProductSizesResponse;

import java.util.List;
import java.util.UUID;

public interface ClientProductService {
    List<NewArrivalProductResponse> getNewArrivals();

    List<NewArrivalProductResponse> getAllProducts();

    ClientProductDetailResponse getProductDetail(UUID uuid) throws VeloriaException;

    List<NewArrivalProductResponse> getPopularProducts(int page, int size);

    List<ClientCategoryResponse> getCategories();

    ProductSizesResponse getProductSizes(UUID productUuid) throws VeloriaException;
}
