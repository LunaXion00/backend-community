package com.example.community.realtime.integration;

import com.example.community.post.dto.PostRequestDTO;
import com.example.community.post.draft.dto.PostDraftRequestDTO;
import com.example.community.post.draft.service.PostDraftService;
import com.example.community.post.repository.PostRepository;
import com.example.community.post.service.PostService;
import com.example.community.realtime.connection.RealtimeConnection;
import com.example.community.realtime.connection.RealtimeConnectionRegistry;
import com.example.community.realtime.connection.RealtimeInterestType;
import com.example.community.realtime.service.RealtimeStreamService;
import com.example.community.user.entity.User;
import com.example.community.user.entity.UserRole;
import com.example.community.user.entity.UserStatus;
import com.example.community.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
class RealtimePostEventIntegrationTest {

    @Autowired
    PostService postService;

    @Autowired
    PostDraftService postDraftService;

    @Autowired
    RealtimeStreamService realtimeStreamService;

    @Autowired
    RealtimeConnectionRegistry registry;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PostRepository postRepository;

    User actor;
    User recipient;
    SseEmitter recipientEmitter;
    Long createdPostId;

    @BeforeEach
    void setUp() {
        registry.findAll().forEach(connection ->
                registry.remove(connection.getConnectionId(), connection.getEmitter())
        );

        actor = userRepository.save(new User(
                "a" + UUID.randomUUID().toString().replace("-", "").substring(0, 9),
                "",
                UserRole.ROLE_USER,
                UserStatus.ACTIVE
        ));
        recipient = userRepository.save(new User(
                "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 9),
                "",
                UserRole.ROLE_USER,
                UserStatus.ACTIVE
        ));
        recipientEmitter = mock(SseEmitter.class);
    }

    @AfterEach
    void tearDown() {
        registry.findAll().forEach(connection ->
                registry.remove(connection.getConnectionId(), connection.getEmitter())
        );
        if (createdPostId != null) {
            postRepository.deleteById(createdPostId);
        }
        if (actor != null) {
            userRepository.deleteById(actor.getUserId());
        }
        if (recipient != null) {
            userRepository.deleteById(recipient.getUserId());
        }
    }

    @Test
    @DisplayName("실제 게시글 생성 commit 후 recipient의 POST_LIST 연결에 post-created를 전달한다")
    void uploadPublishesPostCreatedAfterCommit() throws Exception {
        connectRecipientToPostList();

        PostRequestDTO request = new PostRequestDTO();
        request.setTitle("realtime post");
        request.setPostBody("realtime body");
        request.setPostImageUrl("");

        long postId = postService.upload(actor.getUserId(), request).getPost().getPostId();
        createdPostId = postId;

        ArgumentCaptor<SseEmitter.SseEventBuilder> eventCaptor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(recipientEmitter).send(eventCaptor.capture());
        List<Object> eventParts = eventCaptor.getValue().build().stream()
                .map(SseEmitter.DataWithMediaType::getData)
                .toList();
        assertThat(eventParts.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .anyMatch(part -> part.contains("event:post-created"))).isTrue();
        Map<?, ?> payload = eventParts.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(payload.get("postId")).isEqualTo(postId);
        assertThat(payload.containsKey("actorUserId")).isFalse();
        assertThat(payload.containsKey("post")).isFalse();
    }

    @Test
    @DisplayName("실제 draft publish commit 후 recipient의 POST_LIST 연결에 post-created를 전달한다")
    void publishDraftPublishesPostCreatedAfterCommit() throws Exception {
        connectRecipientToPostList();

        PostDraftRequestDTO request = new PostDraftRequestDTO();
        request.setTitle("draft realtime");
        request.setPostBody("draft body");
        request.setPostImageUrl("");
        request.setVersion(1);
        postDraftService.saveDraft(actor.getUserId(), request);

        long postId = postDraftService.publishDraft(actor.getUserId(), request)
                .getPost()
                .getPostId();
        createdPostId = postId;

        ArgumentCaptor<SseEmitter.SseEventBuilder> eventCaptor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(recipientEmitter).send(eventCaptor.capture());
        List<Object> eventParts = eventCaptor.getValue().build().stream()
                .map(SseEmitter.DataWithMediaType::getData)
                .toList();
        assertThat(eventParts.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .anyMatch(part -> part.contains("event:post-created"))).isTrue();
        Map<?, ?> payload = eventParts.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(payload.get("postId")).isEqualTo(postId);
        assertThat(payload.containsKey("actorUserId")).isFalse();
        assertThat(payload.containsKey("post")).isFalse();
    }

    private void connectRecipientToPostList() throws Exception {
        realtimeStreamService.connect(recipient.getUserId(), "session-recipient", recipientEmitter);
        RealtimeConnection connection = registry.findAll().stream()
                .findFirst()
                .orElseThrow();
        realtimeStreamService.updateInterest(
                recipient.getUserId(),
                connection.getConnectionId(),
                RealtimeInterestType.POST_LIST,
                null,
                1L
        );
        clearInvocations(recipientEmitter);
    }
}
