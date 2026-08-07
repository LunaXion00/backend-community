package com.example.community.auth.dto;

import com.example.community.global.security.jwt.JwtToken;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginResponseDTO {
    private long userId;
    private JwtToken token;
    private String nickname;
    private String profileImageUrl;
}
