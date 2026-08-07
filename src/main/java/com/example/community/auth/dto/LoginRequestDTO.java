package com.example.community.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LoginRequestDTO {
    @NotBlank(message="email을 입력해주세요.")
    @Email(message="email이 올바르지 않습니다.")
    private String email;
    @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%^&*]).*$", message = "비밀번호는 대소문자, 숫자, 특수문자를 포함해야합니다."
    )
    @Size(min = 8, max = 20, message = "비밀번호는 8~20자 입니다.")
    @NotBlank(message="비밀번호를 입력해주세요.")
    private String password;
}
