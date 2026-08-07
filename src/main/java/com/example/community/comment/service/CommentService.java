package com.example.community.comment.service;

import com.example.community.comment.dto.CommentDTO;
import com.example.community.comment.dto.CommentRemoveResponseDTO;
import com.example.community.comment.dto.CommentRequestDTO;
import com.example.community.comment.dto.CommentResponseDTO;
import com.example.community.comment.entity.Comment;
import com.example.community.comment.factory.CommentFactory;
import com.example.community.comment.repository.CommentRepository;
import com.example.community.global.security.AuthValidator;
import com.example.community.global.dto.AuthorDTO;
import com.example.community.global.exceptions.ContentNotFoundException;
import com.example.community.global.exceptions.InvalidInputException;
import com.example.community.global.exceptions.NotRegisteredException;
import com.example.community.global.mapper.AuthorMapper;
import com.example.community.post.entity.Post;
import com.example.community.post.repository.PostRepository;
import com.example.community.realtime.event.CommentCreatedEvent;
import com.example.community.user.entity.User;
import com.example.community.user.repository.UserRepository;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@Validated
public class CommentService {
    private final CommentRepository commentRepository;
    private final UserRepository userRepository;
    private final PostRepository postRepository;
    private final AuthValidator authValidator;
    private final CommentFactory commentFactory;
    private final AuthorMapper authorMapper;
    private final ApplicationEventPublisher eventPublisher;

    public CommentService(CommentRepository commentRepository, UserRepository userRepository, PostRepository postRepository, AuthValidator authValidator, CommentFactory commentFactory, AuthorMapper authorMapper, ApplicationEventPublisher eventPublisher) {
        this.commentRepository = commentRepository;
        this.userRepository = userRepository;
        this.postRepository = postRepository;
        this.authValidator = authValidator;
        this.commentFactory = commentFactory;
        this.authorMapper = authorMapper;
        this.eventPublisher = eventPublisher;
    }
    // ----------------------------------- 댓글 작성 -----------------------------------
    @Transactional
    public CommentResponseDTO uploadComment(Long postId, Long authorId, @Valid CommentRequestDTO commentRequestDTO) {
        User author = userRepository.findById(authorId).orElseThrow(NotRegisteredException::new);
        Post post = postRepository.findById(postId).orElseThrow(ContentNotFoundException::new);
        Comment parentComment = null;
        if (commentRequestDTO.getParentCommentId() != null) {
            parentComment = commentRepository.findCommentWithPost(postId, commentRequestDTO.getParentCommentId()).orElseThrow(ContentNotFoundException::new);
            if (parentComment.isDeleted()) {
                throw new InvalidInputException();
            }
        }
        Comment comment = commentFactory.create(author, post, parentComment, commentRequestDTO);
        commentRepository.save(comment);
        post.increaseComments();
        eventPublisher.publishEvent(new CommentCreatedEvent(
                UUID.randomUUID().toString(),
                post.getPostId(),
                comment.getCommentId(),
                author.getUserId()
        ));
        return new CommentResponseDTO(authorMapper.toAuthorDTO(author), toCommentDTO(comment));
    }
    // ----------------------------------- 댓글 조회 -----------------------------------
    @Transactional(readOnly = true)
    public List<CommentResponseDTO> getComments(Long postId) {
        postRepository.findById(postId).orElseThrow(ContentNotFoundException::new);
        return commentRepository.findListByPost(postId).stream().map(this::toCommentResponseDTO).toList();
    }
    // ----------------------------------- 댓글 수정 -----------------------------------
    @Transactional
    public CommentResponseDTO modifyComment(long postId, long commentId, long loginUserId , @Valid CommentRequestDTO commentRequestDTO) {
        postRepository.findById(postId).orElseThrow(ContentNotFoundException::new);
        Comment comment = commentRepository.findCommentWithPost(postId, commentId).orElseThrow(ContentNotFoundException::new);

        authValidator.validateOwner(loginUserId, comment.getAuthor().getUserId());

        comment.modify(commentRequestDTO.getCommentBody());

        return toCommentResponseDTO(comment);
    }

    // ----------------------------------- 댓글 삭제 -----------------------------------
    @Transactional
    public CommentRemoveResponseDTO deleteComment(long postId, long commentId, long loginUserId) {
        Comment comment = commentRepository.findCommentWithPost(postId, commentId).orElseThrow(ContentNotFoundException::new);
        authValidator.validateOwner(loginUserId, comment.getAuthor().getUserId());
        comment.delete();

        return new CommentRemoveResponseDTO(comment.getCommentId(), true, LocalDateTime.now());
    }

    // ----------------------------------- 추가 메서드 -----------------------------------
    private CommentResponseDTO toCommentResponseDTO(Comment comment) {
        User author = userRepository.findById(comment.getAuthor().getUserId()).orElseThrow(NotRegisteredException::new);
        // 공통 mapper로 사용.
        AuthorDTO authorDTO = authorMapper.toAuthorDTO(author);
        CommentDTO commentDTO = toCommentDTO(comment);
        return new CommentResponseDTO(authorDTO, commentDTO);
    }
    private CommentDTO toCommentDTO(Comment comment) {
        return new CommentDTO(
                comment.getCommentId(),
                comment.getParentComment() == null ? null : comment.getParentComment().getCommentId(),
                comment.getCommentBody(),
                comment.getCreatedAt(),
                comment.isModified(),
                comment.getModifiedAt(),
                comment.isDeleted(),
                comment.getDeletedAt()
        );
    }
}
