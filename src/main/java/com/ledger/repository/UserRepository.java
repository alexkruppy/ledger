package com.ledger.repository;


import com.ledger.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    @Modifying
    @Query("DELETE FROM User u WHERE u.email <> 'fees@ledger.internal'")
    int deleteTestUsers();
}