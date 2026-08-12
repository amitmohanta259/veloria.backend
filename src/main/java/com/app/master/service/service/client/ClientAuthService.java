package com.app.master.service.service.client;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.client.ClientPasswordLoginRequest;
import com.app.master.service.core.request.client.ClientRegisterRequest;
import com.app.master.service.core.request.client.SendOtpRequest;
import com.app.master.service.core.request.client.VerifyOtpRequest;
import com.app.master.service.core.response.client.ClientAuthResponse;

public interface ClientAuthService {

    void register(ClientRegisterRequest request) throws VeloriaException;

    ClientAuthResponse loginWithPassword(ClientPasswordLoginRequest request) throws VeloriaException;

    void sendOtp(SendOtpRequest request) throws VeloriaException;

    ClientAuthResponse verifyOtp(VerifyOtpRequest request) throws VeloriaException;

}
