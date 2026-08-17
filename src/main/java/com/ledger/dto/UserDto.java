package com.ledger.dto;

import com.ledger.model.User;

import java.time.Instant;

public record UserDto(Long id, String email, String firstName, String lastName, String role, String status,
                      Instant createdAt) {

    public static UserDto from(User u) {
        return new UserDto(u.getId(), u.getEmail(), u.getFirstName(), u.getLastName(),
                u.getRole().name(), u.getStatus().name(), u.getCreatedAt());
    }
}
