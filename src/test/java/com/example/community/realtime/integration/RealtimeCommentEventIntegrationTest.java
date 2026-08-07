package com.example.community.realtime.integration;

import com.example.community.comment.dto.CommentRequestDTO;
import com.example.community.comment.dto.CommentResponseDTO;
import com.example.community.comment.repository.CommentRepository;
import com.example.community.comment.service.CommentService;
import com.example.community.post.entity.Post;
import com.example.community.post.repository.PostRepository;
import com.example.community.realtime.connection.RealtimeConnection;
import com.example.community.realtime.connection.RealtimeConnectionRegistry;
import com.example.community.realtime.connection.RealtimeInterestType;
import com.example.community.realtime.service.RealtimeStreamService;
import com.example.community.user.entity.User;
import com.example.community.user.entity.UserRole;
import com.example.community.user.entity.UserStatus;
import com.example.community.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
class RealtimeCommentEventIntegrationTest {

    @Autowired
    CommentService commentService;

    @Autowired
    RealtimeStreamService realtimeStreamService;

    @Autowired
    RealtimeConnectionRegistry registry;

    @Autowired
    UserRepository userRepository;

    @Autowired
    PostRepository postRepository;

    @Autowired
    CommentRepository commentRepository;

    User actor;
    User recipient;
    Post post;
    SseEmitter recipientEmitter;
    List<Long> createdCommentIds;

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
        post = postRepository.save(new Post(actor, "realtime post", "body", ""));
        recipientEmitter = mock(SseEmitter.class);
        createdCommentIds = new ArrayList<>();
    }

    @AfterEach
    void tearDown() {
        registry.findAll().forEach(connection ->
                registry.remove(connection.getConnectionId(), connection.getEmitter())
        );
        createdCommentIds.reversed().forEach(commentRepository::deleteById);
        if (post != null) {
            postRepository.deleteById(post.getPostId());
        }
        if (actor != null) {
            userRepository.deleteById(actor.getUserId());
        }
        if (recipient != null) {
            userRepository.deleteById(recipient.getUserId());
        }
    }

    @Test
    @DisplayName("실제 댓글·대댓글 생성 commit 후 최소 식별자를 전달한다")
    void uploadCommentPublishesCommentCreatedAfterCommit() throws Exception {
        realtimeStreamService.connect(recipient.getUserId(), "session-recipient", recipientEmitter);
        RealtimeConnection connection = registry.findAll().stream()
                .findFirst()
                .orElseThrow();
        realtimeStreamService.updateInterest(
                recipient.getUserId(),
                connection.getConnectionId(),
                RealtimeInterestType.POST_DETAIL,
                post.getPostId(),
                1L
        );
        clearInvocations(recipientEmitter);

        CommentRequestDTO parentRequest = commentRequest("parent", null);
        CommentResponseDTO parentResponse = commentService.uploadComment(
                post.getPostId(),
                actor.getUserId(),
                parentRequest
        );
        createdCommentIds.add(parentResponse.getComment().getCommentId());

        ArgumentCaptor<SseEmitter.SseEventBuilder> parentEventCaptor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(recipientEmitter).send(parentEventCaptor.capture());
        Map<?, ?> parentEvent = eventFrom(parentEventCaptor.getValue());
        assertThat(parentEvent.get("postId")).isEqualTo(post.getPostId());
        assertThat(parentEvent.get("commentId")).isEqualTo(parentResponse.getComment().getCommentId());
        assertThat(parentEvent.containsKey("actorUserId")).isFalse();
        assertThat(parentEvent.containsKey("parentCommentId")).isFalse();
        assertThat(parentEvent.containsKey("comment")).isFalse();

        clearInvocations(recipientEmitter);
        CommentRequestDTO replyRequest = commentRequest(
                "reply",
                parentResponse.getComment().getCommentId()
        );
        CommentResponseDTO replyResponse = commentService.uploadComment(
                post.getPostId(),
                actor.getUserId(),
                replyRequest
        );
        createdCommentIds.add(replyResponse.getComment().getCommentId());

        ArgumentCaptor<SseEmitter.SseEventBuilder> replyEventCaptor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(recipientEmitter).send(replyEventCaptor.capture());
        Map<?, ?> replyEvent = eventFrom(replyEventCaptor.getValue());
        assertThat(replyEvent.get("postId")).isEqualTo(post.getPostId());
        assertThat(replyEvent.get("commentId")).isEqualTo(replyResponse.getComment().getCommentId());
        assertThat(replyEvent.containsKey("actorUserId")).isFalse();
        assertThat(replyEvent.containsKey("parentCommentId")).isFalse();
        assertThat(replyEvent.containsKey("comment")).isFalse();
    }

    private Map<?, ?> eventFrom(SseEmitter.SseEventBuilder eventBuilder) {
        return eventBuilder.build().stream()
                .map(SseEmitter.DataWithMediaType::getData)
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private CommentRequestDTO commentRequest(String body, Long parentCommentId) {
        CommentRequestDTO request = new CommentRequestDTO();
        request.setCommentBody(body);
        request.setParentCommentId(parentCommentId);
        return request;
    }
}
