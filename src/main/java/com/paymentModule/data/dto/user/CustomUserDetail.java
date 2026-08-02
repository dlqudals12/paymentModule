package com.paymentModule.data.dto.user;

import com.paymentModule.data.enums.UserRoleType;
import com.paymentModule.data.model.entity.user.User;
import io.jsonwebtoken.Claims;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

@Getter
@NoArgsConstructor
public class CustomUserDetail implements UserDetails {
    private Long id;
    private String userId;
    private String password;
    private String email;
    private String name;
    private List<UserRoleType> roles;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream().map(roleType -> new SimpleGrantedAuthority(roleType.getValue())).toList();
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return userId;
    }

    public CustomUserDetail(User user) {
        this.id = user.getId();
        this.userId = user.getUserId();
        this.password = user.getUserName();
        this.email = user.getEmail();
        this.name = user.getUserName();
        this.roles = List.of(user.getRole());
    }

    public CustomUserDetail(Claims claims) {
        String joinedRoles = claims.get("roles", String.class);

        this.id = claims.get("id", Long.class);
        this.userId = claims.get("userId", String.class);
        this.email = claims.get("email", String.class);
        this.name = claims.get("name", String.class);
        this.roles = StringUtils.hasText(joinedRoles)
                ? Stream.of(joinedRoles.split(",")).map(UserRoleType::valueOf).toList()
                : List.of();
    }
}
