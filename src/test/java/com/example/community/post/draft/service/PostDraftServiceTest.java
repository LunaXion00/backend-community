package com.example.community.post.draft.service;

import com.example.community.global.security.AuthValidator;
import com.example.community.global.security.jwt.JwtToken;
import com.example.community.global.exceptions.ConflictException;
import com.example.community.global.exceptions.ContentNotFoundException;
import com.example.community.global.exceptions.ForbiddenException;
import com.example.community.global.exceptions.NotRegisteredException;
import com.example.community.post.draft.dto.PostDraftRequestDTO;
import com.example.community.post.draft.dto.PostDraftResponseDTO;
import com.example.community.post.draft.entity.PostDraft;
import com.example.community.post.draft.factory.PostDraftFactory;
import com.example.community.post.draft.repository.PostDraftRepository;
import com.example.community.post.dto.PostResponseDTO;
import com.example.community.post.entity.Post;
import com.example.community.post.factory.PostFactory;
import com.example.community.post.repository.PostRepository;
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
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class PostDraftServiceTest {
    @Mock
    UserRepository userRepository;
    @Mock
    PostRepository postRepository;
    @Mock
    PostDraftRepository postDraftRepository;

    @Mock
    PostDraftFactory postDraftFactory;
    @Mock
    PostFactory postFactory;
    @Mock
    AuthValidator authValidator;
    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    PostDraftService postDraftService;

    User author;
    User otherUser;

    PostDraft draft;
    Post post;

    PostDraftRequestDTO requestDTO;
    PostDraftRequestDTO overwriteRequestDTO;

    @BeforeEach
    void setUp(){
        author = new User(1L, "author", "", UserRole.ROLE_USER, UserStatus.ACTIVE);
        otherUser = new User(2L, "other", "", UserRole.ROLE_USER, UserStatus.ACTIVE);

        draft = new PostDraft(author, "draft title", "draft body", "");
        ReflectionTestUtils.setField(draft, "draftId", 1L);

        post = new Post(author, "draft title", "draft body", "");
        ReflectionTestUtils.setField(post, "postId", 1L);

        requestDTO = new PostDraftRequestDTO();
        requestDTO.setTitle("draft title");
        requestDTO.setPostBody("draft body");
        requestDTO.setPostImageUrl("");
        requestDTO.setVersion(1);

        overwriteRequestDTO = new PostDraftRequestDTO();
        overwriteRequestDTO.setTitle("updated title");
        overwriteRequestDTO.setPostBody("updated body");
        overwriteRequestDTO.setPostImageUrl("updated.png");
        overwriteRequestDTO.setVersion(1);
    }

    @Test
    @DisplayName("현재 임시저장 글이 있으면 반환한다.")
    void getCurrentDraft_exists_returnsDraft() {
        when(postDraftRepository.findByAuthorUserId(author.getUserId())).thenReturn(Optional.of(draft));

        Optional<PostDraftResponseDTO> response = postDraftService.getCurrentDraft(author.getUserId());

        assertThat(response).isPresent();
        assertThat(response.get().getDraftId()).isEqualTo(1L);
        assertThat(response.get().getTitle()).isEqualTo("draft title");
        assertThat(response.get().getPostBody()).isEqualTo("draft body");
        assertThat(response.get().getVersion()).isEqualTo(1);

        verify(postDraftRepository).findByAuthorUserId(author.getUserId());
    }

    @Test
    @DisplayName("현재 임시저장 글이 없으면 Optional.empty를 반환한다.")
    void getCurrentDraft_notExists_returnsEmpty() {
        when(postDraftRepository.findByAuthorUserId(author.getUserId())).thenReturn(Optional.empty());

        Optional<PostDraftResponseDTO> response = postDraftService.getCurrentDraft(author.getUserId());

        assertThat(response).isEmpty();
    }

    @Test
    @DisplayName("임시저장 글 생성 성공")
    void saveDraft_success() {
        when(postDraftRepository.findByAuthorUserId(author.getUserId())).thenReturn(Optional.empty());
        when(userRepository.findById(author.getUserId())).thenReturn(Optional.of(author));
        when(postDraftFactory.create(author, requestDTO)).thenReturn(draft);

        PostDraftResponseDTO response = postDraftService.saveDraft(author.getUserId(), requestDTO);

        assertThat(response.getDraftId()).isEqualTo(1L);
        assertThat(response.getTitle()).isEqualTo("draft title");
        assertThat(response.getPostBody()).isEqualTo("draft body");
        assertThat(response.getVersion()).isEqualTo(1);

        verify(postDraftRepository).save(draft);
    }

    @Test
    @DisplayName("임시저장 글 생성 시 유저가 존재하지 않으면 401")
    void saveDraft_userNotFound_throwsNotRegisteredException() {
        when(postDraftRepository.findByAuthorUserId(author.getUserId())).thenReturn(Optional.empty());
        when(userRepository.findById(author.getUserId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postDraftService.saveDraft(author.getUserId(), requestDTO)).isInstanceOf(NotRegisteredException.class);

        verify(postDraftFactory, never()).create(any(), any());
        verify(postDraftRepository, never()).save(any(PostDraft.class));
    }

    @Test
    @DisplayName("이미 임시저장 글이 있으면 생성 시 409")
    void saveDraft_alreadyExists_throwsConflictException() {
        when(postDraftRepository.findByAuthorUserId(author.getUserId())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> postDraftService.saveDraft(author.getUserId(), requestDTO)).isInstanceOf(ConflictException.class);

        verify(userRepository, never()).findById(anyLong());
        verify(postDraftFactory, never()).create(any(), any());
        verify(postDraftRepository, never()).save(any(PostDraft.class));
    }

    @Test
    @DisplayName("임시저장 글 덮어쓰기 성공")
    void overwriteDraft_success() {
        when(postDraftRepository.findByAuthorUserId(author.getUserId())).thenReturn(Optional.of(draft));

        PostDraftResponseDTO response = postDraftService.overwriteDraft(author.getUserId(), overwriteRequestDTO);

        assertThat(response.getTitle()).isEqualTo("updated title");
        assertThat(response.getPostBody()).isEqualTo("updated body");
        assertThat(response.getPostImageUrl()).isEqualTo("updated.png");
        assertThat(response.getVersion()).isEqualTo(2);
        assertThat(response.getUpdatedAt()).isNotNull();

        verify(authValidator).validateOwner(author.getUserId(), author.getUserId());
    }

    @Test
    @DisplayName("임시저장 글 작성자가 아니면 덮어쓰기 시 403")
    void overwriteDraft_notOwner_throwsForbiddenException() {
        when(postDraftRepository.findByAuthorUserId(otherUser.getUserId())).thenReturn(Optional.of(draft));
        doThrow(new ForbiddenException()).when(authValidator).validateOwner(otherUser.getUserId(), author.getUserId());

        assertThatThrownBy(() -> postDraftService.overwriteDraft(otherUser.getUserId(), overwriteRequestDTO)).isInstanceOf(ForbiddenException.class);

        assertThat(draft.getTitle()).isEqualTo("draft title");
        assertThat(draft.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("덮어쓸 임시저장 글이 없으면 404")
    void overwriteDraft_notFound_throwsContentNotFoundException() {
        when(postDraftRepository.findByAuthorUserId(author.getUserId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postDraftService.overwriteDraft(author.getUserId(), overwriteRequestDTO)).isInstanceOf(ContentNotFoundException.class);

        verify(authValidator, never()).validateOwner(anyLong(), anyLong());
    }

    @Test
    @DisplayName("임시저장 글 발행 성공")
    void publishDraft_success() {
        when(postDraftRepository.findByAuthorUserId(author.getUserId())).thenReturn(Optional.of(draft));
        when(userRepository.findById(author.getUserId())).thenReturn(Optional.of(author));
        when(postFactory.create(author, requestDTO.getTitle(), requestDTO.getPostBody(), requestDTO.getPostImageUrl())).thenReturn(post);

        PostResponseDTO response = postDraftService.publishDraft(author.getUserId(), requestDTO);

        assertThat(response.getAuthor().getNickname()).isEqualTo("author");
        assertThat(response.getPost().getPostId()).isEqualTo(1L);
        assertThat(response.getPost().getTitle()).isEqualTo("draft title");
        assertThat(response.getPost().getPostBody()).isEqualTo("draft body");

        verify(authValidator).validateOwner(author.getUserId(), author.getUserId());

        InOrder inOrder = inOrder(postRepository, postDraftRepository);
        inOrder.verify(postRepository).save(post);
        inOrder.verify(postDraftRepository).delete(draft);
        ArgumentCaptor<PostCreatedEvent> eventCaptor = ArgumentCaptor.forClass(PostCreatedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().postId()).isEqualTo(1L);
        assertThat(eventCaptor.getValue().actorUserId()).isEqualTo(1L);
        assertThat(eventCaptor.getValue().eventId()).isNotBlank();
    }

    @Test
    @DisplayName("임시저장 글 발행 시 유저가 존재하지 않으면 401")
    void publishDraft_userNotFound_throwsNotRegisteredException() {
        when(postDraftRepository.findByAuthorUserId(author.getUserId())).thenReturn(Optional.of(draft));
        when(userRepository.findById(author.getUserId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postDraftService.publishDraft(author.getUserId(), requestDTO)).isInstanceOf(NotRegisteredException.class);

        verify(postFactory, never()).create(any(), anyString(), anyString(), any());
        verify(postRepository, never()).save(any(Post.class));
        verify(postDraftRepository, never()).delete(any(PostDraft.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("발행할 임시저장 글이 없으면 404")
    void publishDraft_notFound_throwsContentNotFoundException() {
        when(postDraftRepository.findByAuthorUserId(author.getUserId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postDraftService.publishDraft(author.getUserId(), requestDTO)).isInstanceOf(ContentNotFoundException.class);

        verify(authValidator, never()).validateOwner(anyLong(), anyLong());
        verify(userRepository, never()).findById(anyLong());
        verify(postRepository, never()).save(any(Post.class));
    }

    @Test
    @DisplayName("임시저장 글 삭제 성공")
    void deleteDraft_success() {
        when(postDraftRepository.findByAuthorUserId(author.getUserId())).thenReturn(Optional.of(draft));

        assertThatCode(() -> postDraftService.deleteDraft(author.getUserId())).doesNotThrowAnyException();

        verify(authValidator).validateOwner(author.getUserId(), author.getUserId());
        verify(postDraftRepository).delete(draft);
    }

    @Test
    @DisplayName("임시저장 글 작성자가 아니면 삭제 시 403")
    void deleteDraft_notOwner_throwsForbiddenException() {
        when(postDraftRepository.findByAuthorUserId(otherUser.getUserId())).thenReturn(Optional.of(draft));
        doThrow(new ForbiddenException()).when(authValidator).validateOwner(otherUser.getUserId(), author.getUserId());

        assertThatThrownBy(() -> postDraftService.deleteDraft(otherUser.getUserId())).isInstanceOf(ForbiddenException.class);

        verify(postDraftRepository, never()).delete(any(PostDraft.class));
    }

    @Test
    @DisplayName("삭제할 임시저장 글이 없으면 404")
    void deleteDraft_notFound_throwsContentNotFoundException() {
        when(postDraftRepository.findByAuthorUserId(author.getUserId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postDraftService.deleteDraft(author.getUserId())).isInstanceOf(ContentNotFoundException.class);

        verify(authValidator, never()).validateOwner(anyLong(), anyLong());
        verify(postDraftRepository, never()).delete(any(PostDraft.class));
    }
}
