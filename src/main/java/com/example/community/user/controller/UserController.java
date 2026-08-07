package com.example.community.user.controller;

import com.example.community.global.ApiResponse;
import com.example.community.user.dto.*;
import com.example.community.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<SignUpResponseDTO>> signUp(@Valid @RequestBody SignUpRequestDTO requestDTO) {
        SignUpResponseDTO responseDTO = userService.signUp(requestDTO);
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>("user_register_success", responseDTO));
    }
    @PatchMapping("/{userId}/info")
    public ResponseEntity<ApiResponse<ModifyInfoResponseDTO>> modifyInfo(Authentication authentication, @PathVariable Long userId, @Valid @RequestBody ModifyInfoRequestDTO requestDTO){
        Long loginUserId = getLoginUserId(authentication);

        ModifyInfoResponseDTO responseDTO = userService.modifyInfo(loginUserId, userId, requestDTO);
        return ResponseEntity.ok().body(new ApiResponse<>("user_modify_success", responseDTO));
    }
    @PatchMapping("/{userId}/password")
    public ResponseEntity<ApiResponse<String>> modifyPassword(Authentication authentication, @PathVariable Long userId, @Valid @RequestBody ModifyPasswordRequestDTO requestDTO){
        Long loginUserId = getLoginUserId(authentication);
        userService.modifyPassword(loginUserId, userId, requestDTO);
        return ResponseEntity.ok().body(new ApiResponse<>("password_modify_success", null));
    }
    @DeleteMapping("/{userId}")
    public ResponseEntity<ApiResponse<WithdrawResponseDTO>>  withdraw(Authentication authentication, @PathVariable Long userId){
        Long loginUserId = getLoginUserId(authentication);
        WithdrawResponseDTO responseDTO = userService.withdraw(loginUserId, userId);
        return ResponseEntity.ok().body(new ApiResponse<>("user_withdraw_success", responseDTO));
    }

    private Long getLoginUserId(Authentication authentication){
        return Long.valueOf(authentication.getName());
    }

}
