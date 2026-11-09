package com.example.demo.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.repository.UserRepository;
import com.example.demo.entity.User;

class IdentityBridgeControllerTest {
    private final UserRepository users = mock(UserRepository.class);
    private final IdentityBridgeController subject =
        new IdentityBridgeController(users, "test-secret");

    @Test void doesNotExposeCredentialsWithoutInternalKey() {
        assertThrows(ResponseStatusException.class,
            () -> subject.user("missing", "admin"));
        verifyNoInteractions(users);
    }

    @Test void returnsCurrentRoleAndHashToTrustedInternalService() {
        User u = User.builder().username("admin").email("admin@example.test")
            .roleId("ADMIN").passwordHash("$2a$10$fake").status("ACTIVE").build();
        when(users.findByUsername("admin")).thenReturn(Optional.of(u));
        var value = subject.user("test-secret", "admin");
        assertEquals("admin", value.username());
        assertEquals("ADMIN", value.role());
        assertEquals("$2a$10$fake", value.passwordHash());
    }
}
