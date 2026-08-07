package com.example.community.global.mapper;

import com.example.community.global.dto.AuthorDTO;
import com.example.community.user.entity.User;
import com.example.community.user.entity.UserStatus;
import org.springframework.stereotype.Component;

@Component
public class AuthorMapper {
    public AuthorDTO toAuthorDTO(User author){
        return toAuthorDTO(
                author.getUserId(),
                author.getStatus(),
                author.getNickname(),
                author.getProfileImageUrl()
        );
    }

    public AuthorDTO toAuthorDTO(Long userId, UserStatus status, String nickname, String profileImageUrl) {
        if (UserStatus.WITHDRAWN.equals(status)) {
            return new AuthorDTO(userId, status, "알 수 없음", null);
        }
        return new AuthorDTO(userId, status, nickname, profileImageUrl);
    }
}
