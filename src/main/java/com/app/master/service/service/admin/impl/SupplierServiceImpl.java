package com.app.master.service.service.admin.impl;

import com.app.master.service.core.dto.Supplier;
import com.app.master.service.core.entity.SupplierEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.response.admin.SupplierListResponse;
import com.app.master.service.core.response.admin.SupplierStatsResponse;
import com.app.master.service.core.service.AppService;
import com.app.master.service.repository.admin.SupplierRepository;
import com.app.master.service.service.admin.SupplierService;
import com.google.common.base.Strings;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class SupplierServiceImpl extends AppService implements SupplierService {

    private final SupplierRepository repository;

    public SupplierServiceImpl(SupplierRepository repository) {
        this.repository = repository;
    }

    @Override
    public void createSupplier(Supplier supplier) throws VeloriaException {
        int next = repository.findMaxSupplierCodeNumber().orElse(0) + 1;
        String code = String.format("SUP-%04d", next);

        SupplierEntity entity = SupplierEntity.builder()
                .supplierCode(code)
                .name(supplier.getName())
                .registrationName(supplier.getRegistrationName())
                .gstn(supplier.getGstn())
                .category(supplier.getCategory())
                .status("ACTIVE")
                .country(supplier.getCountry())
                .city(supplier.getCity())
                .postalAddress(supplier.getPostalAddress())
                .website(supplier.getWebsite())
                .contactName(supplier.getContactName())
                .contactEmail(supplier.getContactEmail())
                .contactPhone(supplier.getContactPhone())
                .paymentTerms(supplier.getPaymentTerms())
                .settlementCurrency(supplier.getSettlementCurrency())
                .bankName(supplier.getBankName())
                .swiftCode(supplier.getSwiftCode())
                .accountNumber(supplier.getAccountNumber())
                .productTags(supplier.getProductTags())
                .leadTime(supplier.getLeadTime())
                .moq(supplier.getMoq())
                .notes(supplier.getNotes())
                .build();

        repository.save(entity);
    }

    @Override
    public void updateSupplier(UUID uuid, Supplier supplier) throws VeloriaException {
        SupplierEntity existing = repository.findByUuid(uuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid supplier uuid"));

        if (!Strings.isNullOrEmpty(supplier.getName())) existing.setName(supplier.getName());
        if (!Strings.isNullOrEmpty(supplier.getRegistrationName())) existing.setRegistrationName(supplier.getRegistrationName());
        if (!Strings.isNullOrEmpty(supplier.getGstn())) existing.setGstn(supplier.getGstn());
        if (!Strings.isNullOrEmpty(supplier.getCategory())) existing.setCategory(supplier.getCategory());
        if (!Strings.isNullOrEmpty(supplier.getStatus())) existing.setStatus(supplier.getStatus());
        if (!Strings.isNullOrEmpty(supplier.getCountry())) existing.setCountry(supplier.getCountry());
        if (!Strings.isNullOrEmpty(supplier.getCity())) existing.setCity(supplier.getCity());
        if (supplier.getPostalAddress() != null) existing.setPostalAddress(supplier.getPostalAddress());
        if (supplier.getWebsite() != null) existing.setWebsite(supplier.getWebsite());
        if (!Strings.isNullOrEmpty(supplier.getContactName())) existing.setContactName(supplier.getContactName());
        if (supplier.getContactEmail() != null) existing.setContactEmail(supplier.getContactEmail());
        if (supplier.getContactPhone() != null) existing.setContactPhone(supplier.getContactPhone());
        if (supplier.getPaymentTerms() != null) existing.setPaymentTerms(supplier.getPaymentTerms());
        if (supplier.getSettlementCurrency() != null) existing.setSettlementCurrency(supplier.getSettlementCurrency());
        if (supplier.getBankName() != null) existing.setBankName(supplier.getBankName());
        if (supplier.getSwiftCode() != null) existing.setSwiftCode(supplier.getSwiftCode());
        if (supplier.getAccountNumber() != null) existing.setAccountNumber(supplier.getAccountNumber());
        if (supplier.getProductTags() != null) existing.setProductTags(supplier.getProductTags());
        if (supplier.getLeadTime() != null) existing.setLeadTime(supplier.getLeadTime());
        if (supplier.getMoq() != null) existing.setMoq(supplier.getMoq());
        if (supplier.getNotes() != null) existing.setNotes(supplier.getNotes());

        existing.setModified(Instant.now());
        repository.save(existing);
    }

    @Override
    public Supplier byUuidSupplier(UUID uuid) throws VeloriaException {
        SupplierEntity existing = repository.findByUuid(uuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid supplier uuid"));

        return Supplier.builder()
                .uuid(existing.getUuid())
                .supplierCode(existing.getSupplierCode())
                .name(existing.getName())
                .registrationName(existing.getRegistrationName())
                .gstn(existing.getGstn())
                .category(existing.getCategory())
                .status(existing.getStatus())
                .country(existing.getCountry())
                .city(existing.getCity())
                .postalAddress(existing.getPostalAddress())
                .website(existing.getWebsite())
                .contactName(existing.getContactName())
                .contactEmail(existing.getContactEmail())
                .contactPhone(existing.getContactPhone())
                .paymentTerms(existing.getPaymentTerms())
                .settlementCurrency(existing.getSettlementCurrency())
                .bankName(existing.getBankName())
                .swiftCode(existing.getSwiftCode())
                .accountNumber(existing.getAccountNumber())
                .productTags(existing.getProductTags())
                .leadTime(existing.getLeadTime())
                .moq(existing.getMoq())
                .notes(existing.getNotes())
                .build();
    }

    @Override
    public Page<SupplierListResponse> allSuppliers(int page, int pageSize, String search, String category) throws VeloriaException {
        Pageable pageable = PageRequest.of(page, pageSize);
        String searchParam = Strings.isNullOrEmpty(search) ? null : search.toLowerCase();
        String categoryParam = (Strings.isNullOrEmpty(category) || "All".equalsIgnoreCase(category)) ? null : category;
        return repository.allSuppliers(searchParam, categoryParam, pageable);
    }

    @Override
    public List<SupplierListResponse> listAllSuppliers() throws VeloriaException {
        return repository.listAllSuppliers();
    }

    @Override
    public void deleteSupplier(UUID uuid) throws VeloriaException {
        SupplierEntity existing = repository.findByUuid(uuid)
                .orElseThrow(() -> new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid supplier uuid"));
        existing.setStatus("TERMINATED");
        existing.setActive(false);
        existing.setModified(Instant.now());
        repository.save(existing);
    }

    @Override
    public SupplierStatsResponse getSupplierStats() throws VeloriaException {
        long total = repository.countByArchiveFalse();
        long active = repository.countByStatusAndArchiveFalse("ACTIVE");
        long pending = repository.countByStatusAndArchiveFalse("PENDING");
        long terminated = repository.countByStatus("TERMINATED");
        return SupplierStatsResponse.builder()
                .totalPartners(total)
                .activeContracts(active)
                .pendingReview(pending)
                .terminated(terminated)
                .build();
    }
}
