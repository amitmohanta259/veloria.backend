package com.app.master.service.service.client.impl;

import com.app.master.service.core.entity.UserAddressEntity;
import com.app.master.service.core.entity.UserEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.client.SaveAddressRequest;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.response.client.AddressBookEntry;
import com.app.master.service.core.response.client.ProfileResponse;
import com.app.master.service.core.service.AppService;
import com.app.master.service.repository.client.UserAddressRepository;
import com.app.master.service.repository.client.UserRepository;
import com.app.master.service.service.client.ClientProfileService;
import com.app.master.service.service.client.ClientSessionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ClientProfileServiceImpl extends AppService implements ClientProfileService {

    private final ClientSessionStore sessionStore;
    private final UserRepository userRepository;
    private final UserAddressRepository addressRepository;

    @Override
    public ProfileResponse getProfile(String token) throws VeloriaException {
        UserEntity user = resolveUser(token);
        return ProfileResponse.builder()
                .userId(user.getUuid().toString())
                .firstName(user.getFirstName())
                .middleName(user.getMiddleName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .phone(user.getPhone())
                .build();
    }

    @Override
    public List<AddressBookEntry> getAddressBook(String token) throws VeloriaException {
        UserEntity user = resolveUser(token);
        return addressRepository
                .findByUserIdAndArchiveFalseOrderByIsDefaultDescCreatedAtAsc(user.getUuid().toString())
                .stream()
                .map(this::toEntry)
                .toList();
    }

    @Override
    @Transactional
    public AddressBookEntry saveAddress(String token, SaveAddressRequest request) throws VeloriaException {
        UserEntity user = resolveUser(token);
        String userId = user.getUuid().toString();

        if (request.isDefault()) {
            addressRepository
                    .findByUserIdAndArchiveFalseOrderByIsDefaultDescCreatedAtAsc(userId)
                    .forEach(a -> {
                        a.setIsDefault(false);
                        addressRepository.save(a);
                    });
        }

        UserAddressEntity saved = addressRepository.save(UserAddressEntity.builder()
                .userId(userId)
                .receiverName(request.getReceiverName())
                .phone(request.getPhone())
                .address(request.getAddress())
                .isDefault(request.isDefault())
                .active(true)
                .archive(false)
                .createdAt(Instant.now())
                .build());

        return toEntry(saved);
    }

    private UserEntity resolveUser(String token) throws VeloriaException {
        ClientSessionStore.SessionData session = sessionStore.get(token);
        if (session == null) {
            throwError(ResponseCode.UNAUTHORIZED, "Session expired. Please sign in again.");
        }
        return userRepository.findByEmailAndArchiveFalse(session.email())
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND, "User not found."));
    }

    private AddressBookEntry toEntry(UserAddressEntity a) {
        return AddressBookEntry.builder()
                .uuid(a.getUuid())
                .receiverName(a.getReceiverName())
                .phone(a.getPhone())
                .address(a.getAddress())
                .isDefault(Boolean.TRUE.equals(a.getIsDefault()))
                .build();
    }
}
