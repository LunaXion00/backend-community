package com.example.community.auth.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenHasherTest {
    private final RefreshTokenHasher hasher = new RefreshTokenHasher();

    @Test
    @DisplayName("같은 refresh token은 같은 SHA-256 hash를 만든다")
    void hashIsDeterministic(){
        String hash = hasher.hash("refresh-token");

        assertThat(hash).hasSize(64);
        assertThat(hasher.hash("refresh-token")).isEqualTo(hash);
        assertThat(hash).doesNotContain("refresh-token");
    }

    @Test
    @DisplayName("다른 refresh token은 다른 hash를 만든다")
    void differentTokensHaveDifferentHashes(){
        assertThat(hasher.hash("refresh-token-1"))
                .isNotEqualTo(hasher.hash("refresh-token-2"));
    }
}
