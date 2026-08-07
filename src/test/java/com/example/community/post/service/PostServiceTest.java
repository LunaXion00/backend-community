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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class PostServiceTest {

    @Mock
    PostRepository postRepository;
    @Mock
    PostRepository.PostListProjection postListProjection;
    @Mock
    UserRepository userRepository;
    @Mock
    PostLikeRepository postLikeRepository;
    @Mock
    ReportRepository reportRepository;
    @Mock
    PostRevisionRepository postRevisionRepository;

    @Mock
    PostFactory postFactory;
    @Mock
    PostLikeFactory postLikeFactory;
    @Mock
    ReportFactory reportFactory;
    @Mock
    AuthorMapper authorMapper;
    @Mock
    AuthValidator authValidator;
    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    PostService postService;

    User user;
    User otherUser;
    Post post;
    AuthorDTO authorDTO;
    PostRequestDTO postRequestDTO;
    ReportRequestDTO reportRequestDTO;
    PostLike postLike;
    Report report;

    @BeforeEach
    void setUp() {
        user = new User(1L, "tester", "", UserRole.ROLE_USER, UserStatus.ACTIVE);
        otherUser = new User(2L, "other", "", UserRole.ROLE_USER, UserStatus.ACTIVE);

        post = new Post(user, "title", "body", "");
        ReflectionTestUtils.setField(post, "postId", 1L);

        authorDTO = new AuthorDTO(1L, UserStatus.ACTIVE, "tester", "");

        postRequestDTO = postRequest("title", "body", "");

        reportRequestDTO = new ReportRequestDTO();
        reportRequestDTO.setReason(ReportReason.SPAM);
        reportRequestDTO.setDescription("spam post");

        postLike = new PostLike(user, post);

        report = new Report(post, user, reportRequestDTO.getReason(), reportRequestDTO.getDescription());
        ReflectionTestUtils.setField(report, "reportId", 1L);
    }

    @Test
    @DisplayName("로그인 유저 id로 게시글을 작성한다.")
    void upload_createsPostWithLoginUser() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(postFactory.create(user, postRequestDTO)).thenReturn(post);
        when(authorMapper.toAuthorDTO(user)).thenReturn(authorDTO);

        PostResponseDTO response = postService.upload(1L, postRequestDTO);

        assertThat(response.getAuthor().getNickname()).isEqualTo("tester");
        assertThat(response.getPost().getPostId()).isEqualTo(1L);
        assertThat(response.getPost().getTitle()).isEqualTo("title");

        verify(postFactory).create(user, postRequestDTO);
        verify(postRepository).save(post);
        ArgumentCaptor<PostCreatedEvent> eventCaptor = ArgumentCaptor.forClass(PostCreatedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().postId()).isEqualTo(1L);
        assertThat(eventCaptor.getValue().actorUserId()).isEqualTo(1L);
        assertThat(eventCaptor.getValue().eventId()).isNotBlank();
    }

    @Test
    @DisplayName("게시글 작성 시 로그인 유저가 없으면 예외가 발생한다.")
    void upload_userNotFoundThrowsException() {
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postService.upload(1L, postRequest("title", "body", ""))).isInstanceOf(NotRegisteredException.class);

        verify(postRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("블라인드 게시글은 숨김 제목으로 목록에 반환된다.")
    void getPostList_blindedPostReturnsHiddenTitle() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 6, 14, 0);
        when(postListProjection.getPostId()).thenReturn(1L);
        when(postListProjection.getPostStatus()).thenReturn(PostStatus.BLINDED.name());
        when(postListProjection.getCreatedAt()).thenReturn(createdAt);
        when(postListProjection.getLikes()).thenReturn(2);
        when(postListProjection.getComments()).thenReturn(3);
        when(postListProjection.getViews()).thenReturn(4);
        when(postListProjection.getUserId()).thenReturn(1L);
        when(postListProjection.getUserStatus()).thenReturn(UserStatus.ACTIVE.name());
        when(postListProjection.getNickname()).thenReturn("tester");
        when(postListProjection.getProfileImageUrl()).thenReturn("");
        when(postRepository.findByStatusNot(PostStatus.DELETED.name(), 0L))
                .thenReturn(List.of(postListProjection));
        when(postRepository.countByStatusNot(PostStatus.DELETED)).thenReturn(1L);
        when(authorMapper.toAuthorDTO(1L, UserStatus.ACTIVE, "tester", ""))
                .thenReturn(authorDTO);

        PostPageResponseDTO result = postService.getPostList(0);

        assertThat(result.getPosts()).hasSize(1);
        assertThat(result.getPosts().get(0).getAuthor().getNickname()).isEqualTo("tester");
        assertThat(result.getPosts().get(0).getPost().getPostId()).isEqualTo(1L);
        assertThat(result.getPosts().get(0).getPost().getTitle()).isEqualTo("숨김 처리된 게시글");
        assertThat(result.getPosts().get(0).getPost().getCreatedAt()).isEqualTo(createdAt);
        assertThat(result.getPosts().get(0).getPost().getLikes()).isEqualTo(2);
        assertThat(result.getPage()).isZero();
        assertThat(result.getSize()).isEqualTo(20);
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getTotalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("게시글 목록은 요청 페이지를 20개 고정 크기로 조회한다.")
    void getPostList_usesFixedPageSize() {
        when(postRepository.findByStatusNot(PostStatus.DELETED.name(), 20L))
                .thenReturn(List.of());
        when(postRepository.countByStatusNot(PostStatus.DELETED)).thenReturn(21L);

        PostPageResponseDTO result = postService.getPostList(1);

        assertThat(result.getPosts()).isEmpty();
        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(20);
        assertThat(result.getTotalElements()).isEqualTo(21);
        assertThat(result.getTotalPages()).isEqualTo(2);
        verify(postRepository).findByStatusNot(PostStatus.DELETED.name(), 20L);
        verify(postRepository).countByStatusNot(PostStatus.DELETED);
    }

    @Test
    @DisplayName("게시글 목록의 page가 음수이면 조회하지 않는다.")
    void getPostList_negativePageThrowsException() {
        assertThatThrownBy(() -> postService.getPostList(-1))
                .isInstanceOf(InvalidInputException.class);

        verifyNoInteractions(postRepository);
    }

    @Test
    @DisplayName("게시글 상세를 조회하고 조회수를 증가시킨다.")
    void getPostDetail_returnsDetailAndIncreaseViews() {
        when(postRepository.findByPostId(1L)).thenReturn(Optional.of(post));
        when(authorMapper.toAuthorDTO(user)).thenReturn(authorDTO);

        PostDetailResponseDTO response = postService.getPostDetail(1L, UserRole.ROLE_USER, 1L);

        assertThat(response.getAuthor().getNickname()).isEqualTo("tester");
        assertThat(response.getPost().getTitle()).isEqualTo("title");
        assertThat(response.getMeta().getViews()).isEqualTo(1);
        assertThat(post.getViews()).isEqualTo(1);
    }

    @Test
    @DisplayName("없는 게시글 상세 조회 시 예외가 발생한다.")
    void getPostDetail_notFoundThrowsException() {
        when(postRepository.findByPostId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postService.getPostDetail(1L, UserRole.ROLE_USER, 1L)).isInstanceOf(ContentNotFoundException.class);
    }

    @Test
    @DisplayName("삭제된 게시글 상세 조회 시 예외가 발생한다.")
    void getPostDetail_deletedPostThrowsException() {
        post.deletePost();
        when(postRepository.findByPostId(1L)).thenReturn(Optional.of(post));

        assertThatThrownBy(() -> postService.getPostDetail(1L, UserRole.ROLE_USER, 1L)).isInstanceOf(ContentNotFoundException.class);
    }

    @Test
    @DisplayName("일반 유저는 블라인드 게시글 상세 조회 시 예외가 발생한다.")
    void getPostDetail_blindedPost_userThrowsForbidden() {
        post.blindPost();
        when(postRepository.findByPostId(1L)).thenReturn(Optional.of(post));

        assertThatThrownBy(() -> postService.getPostDetail(1L, UserRole.ROLE_USER, 1L)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("관리자는 블라인드 게시글 상세 조회가 가능하다.")
    void getPostDetail_blindedPost_adminCanAccess() {
        post.blindPost();
        when(postRepository.findByPostId(1L)).thenReturn(Optional.of(post));
        when(authorMapper.toAuthorDTO(user)).thenReturn(authorDTO);

        PostDetailResponseDTO response = postService.getPostDetail(1L, UserRole.ROLE_ADMIN, 1L);

        assertThat(response.getPost().getPostId()).isEqualTo(1L);
        assertThat(response.getPost().getTitle()).isEqualTo("title");
        assertThat(response.getAuthor().getNickname()).isEqualTo("tester");
    }

    @Test
    @DisplayName("작성자는 게시글을 수정할 수 있다.")
    void modifyPost_ownerCanModify() {
        PostRequestDTO request = postRequest("new title", "new body", "");

        when(postRepository.findByPostId(1L)).thenReturn(Optional.of(post));

        PostDetailResponseDTO response = postService.modifyPost(1L, 1L, request);

        assertThat(response.getPost().getTitle()).isEqualTo("new title");
        assertThat(response.getPost().getPostBody()).isEqualTo("new body");
        assertThat(response.getPost().getRevision()).isEqualTo(2);
        verify(postRevisionRepository).save(any(PostRevision.class));
    }

    @Test
    @DisplayName("작성자가 아니면 게시글을 수정할 수 없다.")
    void modifyPost_notOwnerThrowsForbidden() {
        when(postRepository.findByPostId(1L)).thenReturn(Optional.of(post));
        doThrow(new ForbiddenException()).when(authValidator).validateOwner(otherUser.getUserId(), user.getUserId());

        assertThatThrownBy(() -> postService.modifyPost(otherUser.getUserId(), 1L, postRequest("new", "body", ""))).isInstanceOf(ForbiddenException.class);

        verify(postRevisionRepository, never()).save(any(PostRevision.class));
        assertThat(post.getTitle()).isEqualTo("title");
    }

    @Test
    @DisplayName("작성자는 게시글을 삭제할 수 있다.")
    void deletePost_ownerCanDelete() {
        when(postRepository.findById(1L)).thenReturn(Optional.of(post));

        postService.deletePost(1L, 1L);

        assertThat(post.isDeleted()).isTrue();
    }

    @Test
    @DisplayName("작성자가 아니면 게시글을 삭제할 수 없다.")
    void deletePost_notOwnerThrowsForbidden() {
        when(postRepository.findById(1L)).thenReturn(Optional.of(post));
        doThrow(new ForbiddenException()).when(authValidator).validateOwner(otherUser.getUserId(), user.getUserId());

        assertThatThrownBy(() -> postService.deletePost(otherUser.getUserId(), 1L)).isInstanceOf(ForbiddenException.class);

        assertThat(post.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("게시글 좋아요 성공")
    void likePost_success() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(postRepository.findById(1L)).thenReturn(Optional.of(post));
        when(postLikeRepository.existsByUserAndPost(1L, 1L)).thenReturn(false);
        when(postLikeFactory.create(user, post)).thenReturn(postLike);

        LikeResponseDTO response = postService.likePost(1L, 1L);

        assertThat(response.isLiked()).isTrue();
        assertThat(response.getLikes()).isEqualTo(1);
        assertThat(post.getLikes()).isEqualTo(1);

        verify(postLikeRepository).save(postLike);
    }

    @Test
    @DisplayName("이미 좋아요한 게시글이면 409")
    void likePost_alreadyLiked_throwsConflictException() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(postRepository.findById(1L)).thenReturn(Optional.of(post));
        when(postLikeRepository.existsByUserAndPost(1L, 1L)).thenReturn(true);

        assertThatThrownBy(() -> postService.likePost(1L, 1L)).isInstanceOf(ConflictException.class);

        verify(postLikeRepository, never()).save(any(PostLike.class));
        assertThat(post.getLikes()).isZero();
    }

    @Test
    @DisplayName("게시글 좋아요 취소 성공")
    void unlikePost_success() {
        post.increaseLikes();

        when(postRepository.findById(1L)).thenReturn(Optional.of(post));
        when(postLikeRepository.existsByUserAndPost(1L, 1L)).thenReturn(true);

        LikeResponseDTO response = postService.unlikePost(1L, 1L);

        assertThat(response.isLiked()).isFalse();
        assertThat(response.getLikes()).isZero();
        assertThat(post.getLikes()).isZero();

        verify(postLikeRepository).deletePostlike(1L, 1L);
    }

    @Test
    @DisplayName("좋아요하지 않은 게시글은 취소하면 409.")
    void unlikePost_notLiked_throwsConflictException() {
        when(postRepository.findById(1L)).thenReturn(Optional.of(post));
        when(postLikeRepository.existsByUserAndPost(1L, 1L)).thenReturn(false);

        assertThatThrownBy(() -> postService.unlikePost(1L, 1L)).isInstanceOf(ConflictException.class);

        verify(postLikeRepository, never()).deletePostlike(anyLong(), anyLong());
    }

    @Test
    @DisplayName("게시글 신고 성공 시 신고 수가 기준 이상이면 블라인드 처리된다.")
    void reportPost_success_blindsPost() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(postRepository.findById(1L)).thenReturn(Optional.of(post));
        when(reportRepository.existsByPostAndReporter(1L, 1L)).thenReturn(false);
        when(reportFactory.create(post, user, reportRequestDTO)).thenReturn(report);
        when(reportRepository.countByPostPostId(1L)).thenReturn(1L);

        ReportResponseDTO response = postService.reportPost(1L, 1L, reportRequestDTO);

        assertThat(response.getPostId()).isEqualTo(1L);
        assertThat(response.getReportId()).isEqualTo(1L);
        assertThat(response.isBlinded()).isTrue();
        assertThat(post.isBlinded()).isTrue();

        verify(reportRepository).save(report);
    }

    @Test
    @DisplayName("이미 신고한 게시글이면 409")
    void reportPost_alreadyReported_throwsAlreadyReportedException() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(postRepository.findById(1L)).thenReturn(Optional.of(post));
        when(reportRepository.existsByPostAndReporter(1L, 1L)).thenReturn(true);

        assertThatThrownBy(() -> postService.reportPost(1L, 1L, reportRequestDTO)).isInstanceOf(AlreadyReportedException.class);

        verify(reportFactory, never()).create(any(), any(), any());
        verify(reportRepository, never()).save(any(Report.class));
    }

    @Test
    @DisplayName("삭제된 게시글은 신고할 수 없다.")
    void reportPost_deletedPost_throwsContentNotFoundException() {
        post.deletePost();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(postRepository.findById(1L)).thenReturn(Optional.of(post));

        assertThatThrownBy(() -> postService.reportPost(1L, 1L, reportRequestDTO)).isInstanceOf(ContentNotFoundException.class);

        verify(reportRepository, never()).existsByPostAndReporter(anyLong(), anyLong());
    }

    // 테스트용 postRequestDTO 생성기.
    private PostRequestDTO postRequest(String title, String body, String postImageUrl) {
        PostRequestDTO request = new PostRequestDTO();
        request.setTitle(title);
        request.setPostBody(body);
        request.setPostImageUrl(postImageUrl);
        return request;
    }
}
