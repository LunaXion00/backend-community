package com.example.community.post.draft.service;

import com.example.community.global.security.AuthValidator;
import com.example.community.global.dto.AuthorDTO;
import com.example.community.global.exceptions.ConflictException;
import com.example.community.global.exceptions.ContentNotFoundException;
import com.example.community.global.exceptions.NotRegisteredException;
import com.example.community.post.draft.dto.PostDraftRequestDTO;
import com.example.community.post.draft.dto.PostDraftResponseDTO;
import com.example.community.post.draft.entity.PostDraft;
import com.example.community.post.draft.factory.PostDraftFactory;
import com.example.community.post.draft.repository.PostDraftRepository;
import com.example.community.post.dto.PostDTO;
import com.example.community.post.dto.PostResponseDTO;
import com.example.community.post.entity.Post;
import com.example.community.post.factory.PostFactory;
import com.example.community.post.repository.PostRepository;
import com.example.community.realtime.event.PostCreatedEvent;
import com.example.community.user.entity.User;
import com.example.community.user.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.Optional;
import java.util.UUID;

@Service
@Validated
public class PostDraftService {
    private final PostDraftFactory postDraftFactory;
    private final PostDraftRepository postDraftRepository;
    private final AuthValidator authValidator;
    private final UserRepository userRepository;
    private final PostFactory postFactory;
    private final PostRepository postRepository;
    private final ApplicationEventPublisher eventPublisher;

    public PostDraftService(PostDraftFactory postDraftFactory, PostDraftRepository postDraftRepository, AuthValidator authValidator, UserRepository userRepository, PostFactory postFactory, PostRepository postRepository, ApplicationEventPublisher eventPublisher) {
        this.postDraftFactory = postDraftFactory;
        this.postDraftRepository = postDraftRepository;
        this.authValidator = authValidator;
        this.userRepository = userRepository;
        this.postFactory = postFactory;
        this.postRepository = postRepository;
        this.eventPublisher = eventPublisher;
    }

    // ----------------------------------- 임시작성글 조회 -----------------------------------
    @Transactional(readOnly = true)
    public Optional<PostDraftResponseDTO> getCurrentDraft(Long userId){
        return postDraftRepository.findByAuthorUserId(userId).map(this::toResponseDTO);
    }

    // ----------------------------------- 임시작성글 포스팅 -----------------------------------
    @Transactional
    public PostResponseDTO publishDraft(Long userId, PostDraftRequestDTO requestDTO){
        PostDraft postDraft = postDraftRepository.findByAuthorUserId(userId).orElseThrow(ContentNotFoundException::new);
        authValidator.validateOwner(userId, postDraft.getAuthor().getUserId());
        User author = userRepository.findById(userId).orElseThrow(NotRegisteredException::new);

        // post 생성
        Post post = postFactory.create(author, requestDTO.getTitle(), requestDTO.getPostBody(), requestDTO.getPostImageUrl());
        postRepository.save(post);

        // 임시저장글 삭제.
        postDraftRepository.delete(postDraft);
        eventPublisher.publishEvent(new PostCreatedEvent(
                UUID.randomUUID().toString(),
                post.getPostId(),
                author.getUserId()
        ));
        return new PostResponseDTO(new AuthorDTO(author.getUserId(), author.getStatus(), author.getNickname(), author.getProfileImageUrl()), new PostDTO(post));
    }

    // ----------------------------------- 임시작성글 생성 -----------------------------------
    @Transactional
    public PostDraftResponseDTO saveDraft(Long userId, PostDraftRequestDTO requestDTO){
        // 이미 임시저장글이 있는 경우 -> 예외 처리.
        if(postDraftRepository.findByAuthorUserId(userId).isPresent()) throw new ConflictException();

        PostDraft postDraft = postDraftFactory.create(userRepository.findById(userId).orElseThrow(NotRegisteredException::new), requestDTO);
        postDraftRepository.save(postDraft);

        return toResponseDTO(postDraft);
    }

    // ----------------------------------- 임시작성글 업데이트 -----------------------------------
    @Transactional
    public PostDraftResponseDTO overwriteDraft(Long userId, PostDraftRequestDTO requestDTO){
        PostDraft postDraft = postDraftRepository.findByAuthorUserId(userId).orElseThrow(ContentNotFoundException::new);
        authValidator.validateOwner(userId, postDraft.getAuthor().getUserId());
        postDraft.overwrite(requestDTO.getTitle(), requestDTO.getPostBody(), requestDTO.getPostImageUrl());

        return toResponseDTO(postDraft);
    }

    // ----------------------------------- 임시작성글 삭제 -----------------------------------
    @Transactional
    public void deleteDraft(Long userId){
        PostDraft postDraft = postDraftRepository.findByAuthorUserId(userId).orElseThrow(ContentNotFoundException::new);
        authValidator.validateOwner(userId, postDraft.getAuthor().getUserId());
        postDraftRepository.delete(postDraft);
    }
    // ----------------------------------- 추가 메서드 -----------------------------------

    private PostDraftResponseDTO toResponseDTO(PostDraft postDraft) {
        return new PostDraftResponseDTO(
                postDraft.getDraftId(),
                postDraft.getTitle(),
                postDraft.getPostBody(),
                postDraft.getPostImageUrl(),
                postDraft.getUpdatedAt() == null ? postDraft.getCreatedAt(): postDraft.getUpdatedAt() ,
                postDraft.getVersion()
        );
    }
}
