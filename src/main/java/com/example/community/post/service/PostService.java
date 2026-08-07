package com.example.community.post.service;

import com.example.community.global.security.AuthValidator;
import com.example.community.global.dto.AuthorDTO;
import com.example.community.global.exceptions.*;
import com.example.community.global.mapper.AuthorMapper;
import com.example.community.post.dto.*;
import com.example.community.post.entity.*;
import com.example.community.post.factory.PostFactory;
import com.example.community.post.factory.PostLikeFactory;
import com.example.community.post.factory.ReportFactory;
import com.example.community.post.repository.PostLikeRepository;
import com.example.community.post.repository.PostRepository;
import com.example.community.post.repository.PostRevisionRepository;
import com.example.community.post.repository.ReportRepository;
import com.example.community.realtime.event.PostCreatedEvent;
import com.example.community.user.entity.User;
import com.example.community.user.entity.UserRole;
import com.example.community.user.entity.UserStatus;
import com.example.community.user.repository.UserRepository;
import jakarta.validation.Valid;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.UUID;

@Service
@Validated
public class PostService {
    private static final int POST_PAGE_SIZE = 20;
    private final PostRepository postRepository;
    private final AuthValidator authValidator;
    private final UserRepository userRepository;
    private final PostFactory postFactory;
    private final PostLikeRepository postLikeRepository;
    private final ReportRepository reportRepository;
    private final ReportFactory reportFactory;
    private final PostRevisionRepository postRevisionRepository;
    private final AuthorMapper authorMapper;

    private final ApplicationEventPublisher eventPublisher;
    // 테스트 환경을 위해 1로 설정.
    private final int REPORT_BLIND_LIMIT = 1;
    private final PostLikeFactory postLikeFactory;

    public PostService(PostRepository postRepository, AuthValidator authValidator, UserRepository userRepository, PostFactory postFactory, PostLikeRepository postLikeRepository, ReportRepository reportRepository, ReportFactory reportFactory, PostRevisionRepository postRevisionRepository, AuthorMapper authorMapper, ApplicationEventPublisher eventPublisher, PostLikeFactory postLikeFactory) {
        this.postRepository = postRepository;
        this.authValidator = authValidator;
        this.userRepository = userRepository;
        this.postFactory = postFactory;
        this.postLikeRepository = postLikeRepository;
        this.reportRepository = reportRepository;
        this.reportFactory = reportFactory;
        this.postRevisionRepository = postRevisionRepository;
        this.authorMapper = authorMapper;
        this.eventPublisher = eventPublisher;
        this.postLikeFactory = postLikeFactory;
    }
    // ----------------------------------- 게시물 업로드 -----------------------------------
    @Transactional
    public PostResponseDTO upload(Long authorId, @Valid PostRequestDTO postRequestDTO) {
        User author = userRepository.findById(authorId).orElseThrow(NotRegisteredException::new);

        Post post = postFactory.create(author, postRequestDTO);
        postRepository.save(post);

        eventPublisher.publishEvent(new PostCreatedEvent(
                UUID.randomUUID().toString(),
                post.getPostId(),
                author.getUserId()
        ));

        return new PostResponseDTO(authorMapper.toAuthorDTO(author), new PostDTO(post));
    }

    // ----------------------------------- 게시물 목록 조회 -----------------------------------
    @Transactional(readOnly = true)
    public PostPageResponseDTO getPostList(int page){
        if (page < 0) throw new InvalidInputException();

        List<PostListResponseDTO> posts = postRepository.findByStatusNot(
                PostStatus.DELETED.name(),
                (long) page * POST_PAGE_SIZE
        ).stream()
                .map(this::toPostListResponseDTO)
                .toList();
        long totalElements = postRepository.countByStatusNot(PostStatus.DELETED);
        int totalPages = (int) ((totalElements + POST_PAGE_SIZE - 1) / POST_PAGE_SIZE);

        return new PostPageResponseDTO(
                posts,
                page,
                POST_PAGE_SIZE,
                totalElements,
                totalPages
        );
    }

    // ----------------------------------- 게시물 상세 조회 -----------------------------------
    @Transactional
    public PostDetailResponseDTO getPostDetail(Long userId, UserRole loginUserRole, Long postId){
        Post post = postRepository.findByPostId(postId).orElseThrow(ContentNotFoundException::new);

        // 삭제된 게시글은 접근 x
        if(post.isDeleted()) throw new ContentNotFoundException();

        // 블라인드 된 게시글은 권한(관리자)이 있어야만 접근 가능.
        if(post.isBlinded() && !UserRole.ROLE_ADMIN.equals(loginUserRole)) throw new ForbiddenException();

        boolean liked = postLikeRepository.existsByUserAndPost(userId, postId);
        AuthorDTO authorDTO = authorMapper.toAuthorDTO(post.getAuthor());
        post.increaseViews();

        return new PostDetailResponseDTO(authorDTO, new PostDTO(post), toMetaDTO(post, liked));
    }

    // ----------------------------------- 게시물 수정 -----------------------------------
    @Transactional
    public PostDetailResponseDTO modifyPost(Long userId, Long postId, @Valid PostRequestDTO postRequestDTO){
        Post post = postRepository.findByPostId(postId).orElseThrow(ContentNotFoundException::new);
        if(post.isDeleted()) throw new ContentNotFoundException();
        if(post.isBlinded()) throw new ForbiddenException();
        authValidator.validateOwner(userId, post.getAuthor().getUserId());
        PostRevision postRevision = new PostRevision(post);
        postRevisionRepository.save(postRevision);
        boolean liked = postLikeRepository.existsByUserAndPost(userId, postId);

        post.modifyPost(postRequestDTO.getTitle(), postRequestDTO.getPostBody(), postRequestDTO.getPostImageUrl());
        return new PostDetailResponseDTO(authorMapper.toAuthorDTO(post.getAuthor()), new PostDTO(post), toMetaDTO(post, liked));
    }

    // ----------------------------------- 게시물 삭제 -----------------------------------
    @Transactional
    public void deletePost(Long userId, Long postId){
        Post post = postRepository.findById(postId).orElseThrow(ContentNotFoundException::new);
        authValidator.validateOwner(userId, post.getAuthor().getUserId());

        post.deletePost();
    }

    // ----------------------------------- 좋아요 추가 -----------------------------------
    @Transactional
    public LikeResponseDTO likePost(Long userId, Long postId){
        User user =  userRepository.findById(userId).orElseThrow(NotRegisteredException::new);
        Post post = postRepository.findById(postId).orElseThrow(ContentNotFoundException::new);
        if(postLikeRepository.existsByUserAndPost(userId, postId)) throw new ConflictException();

        PostLike postLike = postLikeFactory.create(user, post);
        postLikeRepository.save(postLike);
        post.increaseLikes();
        return new LikeResponseDTO(postId, post.getLikes(), true);
    }
    // ----------------------------------- 좋아요 삭제 -----------------------------------
    @Transactional
    public LikeResponseDTO unlikePost(Long userId, Long postId){
        Post post = postRepository.findById(postId).orElseThrow(ContentNotFoundException::new);
        if (!postLikeRepository.existsByUserAndPost(userId, postId)) throw new ConflictException();
        postLikeRepository.deletePostlike(userId, postId);
        post.decreaseLikes();
        return new LikeResponseDTO(postId, post.getLikes(), false);
    }
    // ----------------------------------- 게시물 신고 -----------------------------------
    @Transactional
    public ReportResponseDTO reportPost(Long reporterId, Long postId, ReportRequestDTO requestDTO){
        User reporter =  userRepository.findById(reporterId).orElseThrow(NotRegisteredException::new);
        Post post =  postRepository.findById(postId).orElseThrow(ContentNotFoundException::new);
        if (post.isDeleted()) throw new ContentNotFoundException();
        // 이미 해당 게시글에 신고했다면 예외 처리.
        if(reportRepository.existsByPostAndReporter(postId, reporterId)) throw new AlreadyReportedException();

        Report report = reportFactory.create(post, reporter, requestDTO);
        reportRepository.save(report);

        boolean blinded = false;
        if(reportRepository.countByPostPostId(postId) >= REPORT_BLIND_LIMIT){
            blinded = true;
            if(!post.isBlinded()) post.blindPost();
        }

        return new ReportResponseDTO(post.getPostId(), report.getReportId(), blinded);
    }

    // ----------------------------------- 추가 메서드 -----------------------------------
    private PostListResponseDTO toPostListResponseDTO(PostRepository.PostListProjection post) {
        AuthorDTO author = authorMapper.toAuthorDTO(
                post.getUserId(),
                UserStatus.valueOf(post.getUserStatus()),
                post.getNickname(),
                post.getProfileImageUrl()
        );
        PostItemDTO postItem = new PostItemDTO(
                post.getPostId(),
                PostStatus.BLINDED.name().equals(post.getPostStatus())
                        ? "숨김 처리된 게시글"
                        : post.getTitle(),
                post.getCreatedAt(),
                post.getLikes(),
                post.getComments(),
                post.getViews()
        );
        return new PostListResponseDTO(author, postItem);
    }

    private MetaDTO toMetaDTO(Post post, boolean liked){
        return new MetaDTO(post.getLikes(), post.getViews(), post.getComments(), liked);
    }
}
