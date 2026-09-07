package com.app.master.service.service.admin.impl;

import com.app.master.service.core.entity.BusinessDetailsEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.admin.BusinessDetailsRequest;
import com.app.master.service.core.response.admin.BusinessDetailsResponse;
import com.app.master.service.core.service.AppService;
import com.app.master.service.repository.admin.BusinessDetailsRepository;
import com.app.master.service.service.admin.BusinessDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BusinessDetailsServiceImpl extends AppService implements BusinessDetailsService {

    private final BusinessDetailsRepository repository;

    @Override
    public BusinessDetailsResponse get() throws VeloriaException {
        return repository.findFirstByArchiveFalseOrderByIdAsc()
                .map(this::toResponse)
                .orElse(BusinessDetailsResponse.builder().build());
    }

    @Override
    public BusinessDetailsResponse save(BusinessDetailsRequest request) throws VeloriaException {
        BusinessDetailsEntity entity = repository.findFirstByArchiveFalseOrderByIdAsc()
                .orElseGet(BusinessDetailsEntity::new);

        entity.setGstNumber(request.getGstNumber());
        entity.setCompanyName(request.getCompanyName());
        entity.setCompanyAddress(request.getCompanyAddress());
        entity.setOwnerName(request.getOwnerName());
        entity.setRegisteredPhone(request.getRegisteredPhone());
        entity.setRegisteredEmail(request.getRegisteredEmail());
        entity.setSellerStateCode(request.getSellerStateCode());
        entity.setPincode(request.getPincode());
        entity.setActive(true);
        entity.setArchive(false);

        return toResponse(repository.save(entity));
    }

    private BusinessDetailsResponse toResponse(BusinessDetailsEntity e) {
        return BusinessDetailsResponse.builder()
                .uuid(e.getUuid())
                .gstNumber(e.getGstNumber())
                .companyName(e.getCompanyName())
                .companyAddress(e.getCompanyAddress())
                .ownerName(e.getOwnerName())
                .registeredPhone(e.getRegisteredPhone())
                .registeredEmail(e.getRegisteredEmail())
                .sellerStateCode(e.getSellerStateCode())
                .pincode(e.getPincode())
                .build();
    }
}
