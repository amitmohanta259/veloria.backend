package com.app.master.service.service;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.AuthRequest;
import com.app.master.service.core.request.RegisterRequest;
import com.app.master.service.core.response.AuthResponse;

public interface AuthService {

    AuthResponse login(AuthRequest request) throws VeloriaException;

    void register(RegisterRequest request) throws VeloriaException;

}
