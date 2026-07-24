package com.paymentModule.domain.user.dto.request;

import com.paymentModule.data.enums.UserRoleType;
import lombok.Getter;
import lombok.NonNull;

@Getter
public class UserSaveRequest {
    @NonNull
    private String id;

    @NonNull
    private String password;

    @NonNull
    private String email;

    @NonNull
    private String name;

    @NonNull
    private UserRoleType role;
}
