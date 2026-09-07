package com.app.master.service.service.admin;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.admin.BusinessDetailsRequest;
import com.app.master.service.core.response.admin.BusinessDetailsResponse;

public interface BusinessDetailsService {
    BusinessDetailsResponse get() throws VeloriaException;
    BusinessDetailsResponse save(BusinessDetailsRequest request) throws VeloriaException;
}
