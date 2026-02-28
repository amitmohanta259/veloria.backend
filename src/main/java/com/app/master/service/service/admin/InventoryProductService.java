package com.app.master.service.service.admin;

import com.app.master.service.core.dto.InventoryProduct;
import com.app.master.service.core.exception.VeloriaException;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface InventoryProductService {

    void createInventoryProduct(InventoryProduct product, List<MultipartFile> images) throws VeloriaException;

}
