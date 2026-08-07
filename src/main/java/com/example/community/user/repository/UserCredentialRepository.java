package com.example.community.user.repository;

import com.example.community.user.entity.UserCredential;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface UserCredentialRepository extends JpaRepository<UserCredential,Long> {
    @EntityGraph(attributePaths = "user")
    @Transactional(readOnly = true)
    Optional<UserCredential> findByEmail(String email);

    boolean existsByEmail(String email);
}
