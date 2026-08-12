package com.app.master.service.service.client.impl;

import com.app.master.service.core.entity.UserEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.client.ClientPasswordLoginRequest;
import com.app.master.service.core.request.client.ClientRegisterRequest;
import com.app.master.service.core.request.client.SendOtpRequest;
import com.app.master.service.core.request.client.VerifyOtpRequest;
import com.app.master.service.core.response.client.ClientAuthResponse;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.service.AppService;
import com.app.master.service.repository.client.UserRepository;
import com.app.master.service.service.client.ClientAuthService;
import com.app.master.service.service.client.ClientNotificationService;
import com.app.master.service.service.client.ClientSessionStore;
import com.app.master.service.service.client.OtpStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClientAuthServiceImpl extends AppService implements ClientAuthService {

    private final UserRepository userRepository;
    private final OtpStore otpStore;
    private final ClientSessionStore sessionStore;
    private final ClientNotificationService notificationService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    @Transactional
    public void register(ClientRegisterRequest request) throws VeloriaException {
        String email = request.getEmail().trim().toLowerCase();
        String phone = request.getPhone().trim().replaceAll("[^0-9]", "");

        if (userRepository.existsByEmailAndArchiveFalse(email)) {
            throwError(ResponseCode.BAD_REQUEST, "An account with this email already exists.");
        }
        if (userRepository.existsByPhoneAndArchiveFalse(phone)) {
            throwError(ResponseCode.BAD_REQUEST, "An account with this phone number already exists.");
        }

        String hash = passwordEncoder.encode(request.getPassword());

        UserEntity user = UserEntity.builder()
                .uuid(UUID.randomUUID())
                .firstName(request.getFirstName().trim())
                .lastName(request.getLastName().trim())
                .userName(email)
                .email(email)
                .phone(phone)
                .passwordHash(hash)
                .active(true)
                .archive(false)
                .build();

        userRepository.save(user);
        log.info("New client registered: {}", email);
    }

    @Override
    public ClientAuthResponse loginWithPassword(ClientPasswordLoginRequest request) throws VeloriaException {
        String email = request.getEmail().trim().toLowerCase();

        UserEntity user = userRepository.findByEmailAndArchiveFalse(email)
                .orElseThrow(() -> new VeloriaException(ResponseCode.UNAUTHORIZED, "Invalid credentials."));

        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throwError(ResponseCode.UNAUTHORIZED, "Invalid credentials.");
        }

        return buildSession(user);
    }

    @Override
    public void sendOtp(SendOtpRequest request) throws VeloriaException {
        String phone = request.getIdentifier().trim().replaceAll("[^0-9]", "");
        if (phone.length() == 12 && phone.startsWith("91")) phone = phone.substring(2);

        UserEntity user = userRepository.findByPhoneAndArchiveFalse(phone)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "No account found with this phone number."));

        String otp = otpStore.generate(phone);
        log.info("OTP generated for phone ending {}*** (dev log)", phone.substring(0, 3));

        notificationService.sendOtpSms(user.getPhone(), otp);
        // Also notify on email for convenience
        if (user.getEmail() != null) {
            notificationService.sendOtpEmail(user.getEmail(), otp);
        }
    }

    @Override
    public ClientAuthResponse verifyOtp(VerifyOtpRequest request) throws VeloriaException {
        String phone = request.getIdentifier().trim().replaceAll("[^0-9]", "");
        if (phone.length() == 12 && phone.startsWith("91")) phone = phone.substring(2);

        if (!otpStore.verify(phone, request.getOtp())) {
            throwError(ResponseCode.UNAUTHORIZED, "Invalid or expired OTP. Please try again.");
        }

        UserEntity user = userRepository.findByPhoneAndArchiveFalse(phone)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "User not found."));

        return buildSession(user);
    }

    private ClientAuthResponse buildSession(UserEntity user) {
        String fullName = user.getFirstName() + (user.getLastName() != null ? " " + user.getLastName() : "");
        String token = sessionStore.create(user.getUuid().toString(), fullName, user.getEmail(), user.getPhone());
        return ClientAuthResponse.builder()
                .token(token)
                .userId(user.getUuid().toString())
                .name(fullName)
                .email(user.getEmail())
                .phone(user.getPhone())
                .build();
    }

}
