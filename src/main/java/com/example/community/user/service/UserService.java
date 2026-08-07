package com.example.community.user.service;

import com.example.community.auth.session.RefreshSessionStore;
import com.example.community.global.security.AuthValidator;
import com.example.community.global.exceptions.*;
import com.example.community.user.dto.*;
import com.example.community.user.entity.User;
import com.example.community.user.entity.UserCredential;
import com.example.community.user.factory.UserCredentialFactory;
import com.example.community.user.factory.UserFactory;
import com.example.community.user.repository.UserCredentialRepository;
import com.example.community.user.repository.UserRepository;
import jakarta.validation.Valid;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;

@Service
@Validated
public class UserService {
    private final UserRepository userRepository;
    private final UserCredentialRepository userCredentialRepository;
    private final AuthValidator authValidator;
    private final UserFactory userFactory;
    private final UserCredentialFactory userCredentialFactory;
    private final PasswordEncoder passwordEncoder;
    private final RefreshSessionStore refreshSessionStore;

    public UserService(UserRepository userRepository, UserCredentialRepository userCredentialRepository, AuthValidator authValidator, UserFactory userFactory, UserCredentialFactory userCredentialFactory, PasswordEncoder passwordEncoder, RefreshSessionStore refreshSessionStore) {
        this.userRepository = userRepository;
        this.userCredentialRepository = userCredentialRepository;
        this.authValidator = authValidator;
        this.userFactory = userFactory;
        this.userCredentialFactory = userCredentialFactory;
        this.passwordEncoder = passwordEncoder;
        this.refreshSessionStore = refreshSessionStore;
    }
    // ----------------------------------- 회원가입(유저 생성) -----------------------------------
    @Transactional
    public SignUpResponseDTO signUp(@Valid SignUpRequestDTO requestDTO){
        if (!requestDTO.getPassword().equals(requestDTO.getPasswordConfirm())) throw new InvalidInputException();
        if(userCredentialRepository.existsByEmail(requestDTO.getEmail()) || userRepository.existsByNickname(requestDTO.getNickname())) throw new AlreadyExistsException();
        User user = userFactory.create(requestDTO);
        userRepository.save(user);
        UserCredential credential = userCredentialFactory.create(user, requestDTO);
        userCredentialRepository.save(credential);

        return new SignUpResponseDTO(user.getUserId());
    }

    // ----------------------------------- 유저 정보 수정(이름, 프로필사진) -----------------------------------
    @Transactional
    public ModifyInfoResponseDTO modifyInfo(Long loginUserId, Long targetUserId, @Valid ModifyInfoRequestDTO requestDTO){
        authValidator.validateOwner(loginUserId, targetUserId);
        String nickname = requestDTO.getNickname();
        String profileImageUrl = requestDTO.getProfileImageUrl();
        if(userRepository.existsByNicknameAndUserIdNot(nickname, targetUserId)) throw new AlreadyExistsException();

        User user =  userRepository.findById(targetUserId).orElseThrow(NotRegisteredException::new);
        LocalDateTime updatedAt = LocalDateTime.now();

        user.modifyProfile(nickname, profileImageUrl);
        return new ModifyInfoResponseDTO(user.getUserId(), user.getNickname(), user.getProfileImageUrl(),  updatedAt);
    }
    // ----------------------------------- 유저 정보 수정(비밀번호) -----------------------------------
    @Transactional
    public void modifyPassword(Long loginUserId, Long targetUserId, @Valid ModifyPasswordRequestDTO requestDTO){
        authValidator.validateOwner(loginUserId, targetUserId);

        UserCredential credential = userCredentialRepository.findById(targetUserId).orElseThrow(NotRegisteredException::new);
        if (passwordEncoder.matches(requestDTO.getPassword(), credential.getPassword())) throw new InvalidInputException();

        credential.modifyPassword(passwordEncoder.encode(requestDTO.getPassword()));
    }
    // ----------------------------------- 유저 탈퇴(유저 삭제) -----------------------------------
    @Transactional
    public WithdrawResponseDTO withdraw(Long loginUserId, Long targetUserId){
        authValidator.validateOwner(loginUserId, targetUserId);
        User user = userRepository.findById(targetUserId).orElseThrow(NotRegisteredException::new);
        user.withDraw();
        refreshSessionStore.deleteByUserId(targetUserId);
        return new WithdrawResponseDTO(LocalDateTime.now());
    }
}
