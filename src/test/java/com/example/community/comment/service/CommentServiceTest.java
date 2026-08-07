package com.example.community.comment.service;

import com.example.community.comment.dto.CommentRemoveResponseDTO;
import com.example.community.comment.dto.CommentRequestDTO;
import com.example.community.comment.dto.CommentResponseDTO;
import com.example.community.comment.entity.Comment;
import com.example.community.comment.factory.CommentFactory;
import com.example.community.comment.repository.CommentRepository;
import com.example.community.global.security.AuthValidator;
import com.example.community.global.security.jwt.JwtToken;
import com.example.community.global.dto.AuthorDTO;
import com.example.community.global.exceptions.ContentNotFoundException;
import com.example.community.global.exceptions.ForbiddenException;
import com.example.community.global.exceptions.InvalidInputException;
import com.example.community.global.exceptions.NotRegisteredException;
import com.example.community.global.mapper.AuthorMapper;
import com.example.community.post.entity.Post;
import com.example.community.post.repository.PostRepository;
import com.example.community.realtime.event.CommentCreatedEvent;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class CommentServiceTest {
    @Mock
    CommentRepository commentRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    PostRepository postRepository;
    @Mock
    CommentFactory commentFactory;
    @Mock
    AuthValidator authValidator;
    @Mock
    AuthorMapper authorMapper;
    @Mock
    ApplicationEventPublisher eventPublisher;

    @InjectMocks
    CommentService commentService;

    User author;
    User commenter;
    Post post;
    Comment comment;
    JwtToken jwtToken;

    CommentRequestDTO commentRequestDTO;
    CommentRequestDTO modifyRequestDTO;

    @BeforeEach
    void setUp(){
        author = new User(1L, "author", "", UserRole.ROLE_USER, UserStatus.ACTIVE);
        post= new Post(author, "test", "testBody", "testImage");
        ReflectionTestUtils.setField(post, "postId", 1L);
        commenter = new User(2L, "commenter", "", UserRole.ROLE_USER, UserStatus.ACTIVE);
        comment = new Comment(commenter, post, null, "test comment");
        ReflectionTestUtils.setField(comment, "commentId", 1L);
        jwtToken = new JwtToken("Bearer", "access-token", "refresh-token");

        commentRequestDTO = new CommentRequestDTO();
        commentRequestDTO.setCommentBody("test comment");

        modifyRequestDTO = new CommentRequestDTO();
        modifyRequestDTO.setCommentBody("modified comment");
    }
    @Test
    @DisplayName("댓글 작성 성공시 post의 댓글 수도 1 오른다.")
    void upload_success(){
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(commenter));
        when(postRepository.findById(anyLong())).thenReturn(Optional.of(post));
        when(commentFactory.create(commenter, post, null, commentRequestDTO)).thenReturn(comment);

        CommentResponseDTO response = commentService.uploadComment(post.getPostId(),commenter.getUserId(), commentRequestDTO);
        assertThat(response.getComment().getCommentBody()).isEqualTo("test comment");
        assertThat(response.getComment().getParentCommentId()).isNull();
        assertThat(post.getComments()).isEqualTo(1);
        verify(commentRepository, never()).findCommentWithPost(anyLong(), anyLong());

        ArgumentCaptor<CommentCreatedEvent> eventCaptor = ArgumentCaptor.forClass(CommentCreatedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().postId()).isEqualTo(post.getPostId());
        assertThat(eventCaptor.getValue().commentId()).isEqualTo(comment.getCommentId());
        assertThat(eventCaptor.getValue().actorUserId()).isEqualTo(commenter.getUserId());
        assertThat(eventCaptor.getValue().eventId()).isNotBlank();
    }

    @Test
    @DisplayName("대댓글 작성 시 직접 부모 ID를 포함한 comment-created를 발행한다")
    void uploadReply_publishesParentCommentId() {
        Comment parent = new Comment(commenter, post, null, "parent comment");
        ReflectionTestUtils.setField(parent, "commentId", 10L);
        Comment reply = new Comment(commenter, post, parent, "reply");
        ReflectionTestUtils.setField(reply, "commentId", 11L);
        CommentRequestDTO request = commentRequest("reply", parent.getCommentId());
        when(userRepository.findById(commenter.getUserId())).thenReturn(Optional.of(commenter));
        when(postRepository.findById(post.getPostId())).thenReturn(Optional.of(post));
        when(commentRepository.findCommentWithPost(post.getPostId(), parent.getCommentId())).thenReturn(Optional.of(parent));
        when(commentFactory.create(commenter, post, parent, request)).thenReturn(reply);

        commentService.uploadComment(post.getPostId(), commenter.getUserId(), request);

        ArgumentCaptor<CommentCreatedEvent> eventCaptor = ArgumentCaptor.forClass(CommentCreatedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().postId()).isEqualTo(post.getPostId());
        assertThat(eventCaptor.getValue().commentId()).isEqualTo(reply.getCommentId());
        assertThat(eventCaptor.getValue().actorUserId()).isEqualTo(commenter.getUserId());
    }

    @Test
    @DisplayName("같은 부모에 여러 대댓글을 작성하고 대댓글에도 답글을 작성할 수 있다.")
    void uploadComment_allowsMultipleChildrenAndNestedReply() {
        Comment parent = new Comment(commenter, post, null, "parent comment");
        ReflectionTestUtils.setField(parent, "commentId", 10L);
        Comment firstReply = new Comment(commenter, post, parent, "first reply");
        ReflectionTestUtils.setField(firstReply, "commentId", 11L);
        Comment secondReply = new Comment(commenter, post, parent, "second reply");
        ReflectionTestUtils.setField(secondReply, "commentId", 12L);
        Comment nestedReply = new Comment(commenter, post, firstReply, "nested reply");
        ReflectionTestUtils.setField(nestedReply, "commentId", 13L);

        CommentRequestDTO firstRequest = commentRequest("first reply", parent.getCommentId());
        CommentRequestDTO secondRequest = commentRequest("second reply", parent.getCommentId());
        CommentRequestDTO nestedRequest = commentRequest("nested reply", firstReply.getCommentId());

        when(userRepository.findById(commenter.getUserId())).thenReturn(Optional.of(commenter));
        when(postRepository.findById(post.getPostId())).thenReturn(Optional.of(post));
        when(commentRepository.findCommentWithPost(post.getPostId(), parent.getCommentId())).thenReturn(Optional.of(parent));
        when(commentRepository.findCommentWithPost(post.getPostId(), firstReply.getCommentId())).thenReturn(Optional.of(firstReply));
        when(commentFactory.create(commenter, post, parent, firstRequest)).thenReturn(firstReply);
        when(commentFactory.create(commenter, post, parent, secondRequest)).thenReturn(secondReply);
        when(commentFactory.create(commenter, post, firstReply, nestedRequest)).thenReturn(nestedReply);

        CommentResponseDTO firstResponse = commentService.uploadComment(post.getPostId(), commenter.getUserId(), firstRequest);
        CommentResponseDTO secondResponse = commentService.uploadComment(post.getPostId(), commenter.getUserId(), secondRequest);
        CommentResponseDTO nestedResponse = commentService.uploadComment(post.getPostId(), commenter.getUserId(), nestedRequest);

        assertThat(firstReply.getParentComment()).isSameAs(parent);
        assertThat(secondReply.getParentComment()).isSameAs(parent);
        assertThat(nestedReply.getParentComment()).isSameAs(firstReply);
        assertThat(firstResponse.getComment().getParentCommentId()).isEqualTo(parent.getCommentId());
        assertThat(secondResponse.getComment().getParentCommentId()).isEqualTo(parent.getCommentId());
        assertThat(nestedResponse.getComment().getParentCommentId()).isEqualTo(firstReply.getCommentId());
        assertThat(post.getComments()).isEqualTo(3);
        verify(commentRepository, times(2)).findCommentWithPost(post.getPostId(), parent.getCommentId());
        verify(commentRepository).findCommentWithPost(post.getPostId(), firstReply.getCommentId());
        verify(commentRepository).save(firstReply);
        verify(commentRepository).save(secondReply);
        verify(commentRepository).save(nestedReply);
        verify(eventPublisher, times(3)).publishEvent(any(CommentCreatedEvent.class));
    }

    @Test
    @DisplayName("부모 댓글이 존재하지 않으면 저장하지 않고 404")
    void uploadComment_parentNotFound_doesNotSaveOrIncreaseCount() {
        CommentRequestDTO request = commentRequest("reply", 999L);
        when(userRepository.findById(commenter.getUserId())).thenReturn(Optional.of(commenter));
        when(postRepository.findById(post.getPostId())).thenReturn(Optional.of(post));
        when(commentRepository.findCommentWithPost(post.getPostId(), 999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.uploadComment(post.getPostId(), commenter.getUserId(), request))
                .isInstanceOf(ContentNotFoundException.class);

        assertThat(post.getComments()).isZero();
        verify(commentFactory, never()).create(any(), any(), any(), any());
        verify(commentRepository, never()).save(any(Comment.class));
        verify(eventPublisher, never()).publishEvent(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("다른 게시글의 댓글은 부모로 지정할 수 없고 저장하지 않는다.")
    void uploadComment_parentFromAnotherPost_doesNotSaveOrIncreaseCount() {
        Post otherPost = new Post(author, "other", "other body", "");
        ReflectionTestUtils.setField(otherPost, "postId", 2L);
        Comment otherPostComment = new Comment(commenter, otherPost, null, "other post comment");
        ReflectionTestUtils.setField(otherPostComment, "commentId", 20L);
        CommentRequestDTO request = commentRequest("reply", otherPostComment.getCommentId());
        when(userRepository.findById(commenter.getUserId())).thenReturn(Optional.of(commenter));
        when(postRepository.findById(post.getPostId())).thenReturn(Optional.of(post));
        when(commentRepository.findCommentWithPost(post.getPostId(), otherPostComment.getCommentId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.uploadComment(post.getPostId(), commenter.getUserId(), request))
                .isInstanceOf(ContentNotFoundException.class);

        assertThat(post.getComments()).isZero();
        verify(commentRepository).findCommentWithPost(post.getPostId(), otherPostComment.getCommentId());
        verify(commentFactory, never()).create(any(), any(), any(), any());
        verify(commentRepository, never()).save(any(Comment.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("삭제된 댓글은 부모로 지정할 수 없고 저장하지 않는다.")
    void uploadComment_deletedParent_doesNotSaveOrIncreaseCount() {
        Comment deletedParent = new Comment(commenter, post, null, "deleted parent");
        ReflectionTestUtils.setField(deletedParent, "commentId", 30L);
        deletedParent.delete();
        CommentRequestDTO request = commentRequest("reply", deletedParent.getCommentId());
        when(userRepository.findById(commenter.getUserId())).thenReturn(Optional.of(commenter));
        when(postRepository.findById(post.getPostId())).thenReturn(Optional.of(post));
        when(commentRepository.findCommentWithPost(post.getPostId(), deletedParent.getCommentId())).thenReturn(Optional.of(deletedParent));

        assertThatThrownBy(() -> commentService.uploadComment(post.getPostId(), commenter.getUserId(), request))
                .isInstanceOf(InvalidInputException.class);

        assertThat(post.getComments()).isZero();
        verify(commentFactory, never()).create(any(), any(), any(), any());
        verify(commentRepository, never()).save(any(Comment.class));
    }

    @Test
    @DisplayName("댓글 작성 시 작성자가 존재하지 않으면 401")
    void uploadComment_authorNotFound_throwsNotRegisteredException() {
        when(userRepository.findById(commenter.getUserId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.uploadComment(post.getPostId(), commenter.getUserId(), commentRequestDTO)).isInstanceOf(NotRegisteredException.class);

        verify(postRepository, never()).findById(anyLong());
        verify(commentFactory, never()).create(any(), any(), any(), any());
        verify(commentRepository, never()).save(any(Comment.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("댓글 작성 시 게시글이 존재하지 않으면 404")
    void uploadComment_postNotFound_throwsContentNotFoundException() {
        when(userRepository.findById(commenter.getUserId())).thenReturn(Optional.of(commenter));
        when(postRepository.findById(post.getPostId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.uploadComment(post.getPostId(), commenter.getUserId(), commentRequestDTO)).isInstanceOf(ContentNotFoundException.class);

        verify(commentFactory, never()).create(any(), any(), any(), any());
        verify(commentRepository, never()).save(any(Comment.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("댓글 작성 실패 시 post의 댓글 수가 오르지 않는다.")
    void upload_fail(){
        DataAccessResourceFailureException saveException = new DataAccessResourceFailureException("comment save failed");
        when(userRepository.findById(anyLong())).thenReturn(Optional.of(commenter));
        when(postRepository.findById(anyLong())).thenReturn(Optional.of(post));
        when(commentFactory.create(commenter, post, null, commentRequestDTO)).thenReturn(comment);
        when(commentRepository.save(any())).thenThrow(saveException);

        assertThatThrownBy(()->commentService.uploadComment(post.getPostId(), commenter.getUserId(), commentRequestDTO)).isSameAs(saveException);
        assertThat(post.getComments()).isEqualTo(0);
        verify(commentRepository).save(comment);
        verify(eventPublisher, never()).publishEvent(any());
    }
    @Test
    @DisplayName("댓글 조회 성공")
    void getCommentList_success(){
        Comment reply = new Comment(commenter, post, comment, "reply");
        ReflectionTestUtils.setField(reply, "commentId", 2L);
        Comment nestedReply = new Comment(commenter, post, reply, "nested reply");
        ReflectionTestUtils.setField(nestedReply, "commentId", 3L);
        when(postRepository.findById(post.getPostId())).thenReturn(Optional.of(post));
        when(commentRepository.findListByPost(post.getPostId())).thenReturn(List.of(comment, reply, nestedReply));
        when(userRepository.findById(commenter.getUserId())).thenReturn(Optional.of(commenter));
        when(authorMapper.toAuthorDTO(commenter)).thenReturn(new AuthorDTO(2L, UserStatus.ACTIVE, "commenter", ""));

        List<CommentResponseDTO> response = commentService.getComments(post.getPostId());

        assertThat(response).hasSize(3);
        assertThat(response.getFirst().getAuthor().getNickname()).isEqualTo("commenter");
        assertThat(response.getFirst().getComment().getCommentId()).isEqualTo(comment.getCommentId());
        assertThat(response.getFirst().getComment().getParentCommentId()).isNull();
        assertThat(response.getFirst().getComment().getCommentBody()).isEqualTo("test comment");
        assertThat(response.get(0).getComment().isModified()).isFalse();
        assertThat(response.get(0).getComment().isDeleted()).isFalse();
        assertThat(response.get(1).getComment().getParentCommentId()).isEqualTo(comment.getCommentId());
        assertThat(response.get(2).getComment().getParentCommentId()).isEqualTo(reply.getCommentId());

        verify(postRepository).findById(post.getPostId());
        verify(commentRepository).findListByPost(post.getPostId());
        verify(userRepository, times(3)).findById(commenter.getUserId());
    }

    @Test
    @DisplayName("댓글 조회 시 게시글이 존재하지 않으면 404")
    void getComments_postNotFound_throwsContentNotFoundException() {
        when(postRepository.findById(post.getPostId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> commentService.getComments(post.getPostId())).isInstanceOf(ContentNotFoundException.class);

        verify(commentRepository, never()).findListByPost(anyLong());
    }

    @Test
    @DisplayName("댓글 수정 성공")
    void modifyComment_success(){
        Comment originalParent = new Comment(commenter, post, null, "original parent");
        ReflectionTestUtils.setField(originalParent, "commentId", 10L);
        Comment reply = new Comment(commenter, post, originalParent, "test comment");
        ReflectionTestUtils.setField(reply, "commentId", 1L);
        modifyRequestDTO.setParentCommentId(11L);
        when(postRepository.findById(anyLong())).thenReturn(Optional.of(post));
        when(userRepository.findById(2L)).thenReturn(Optional.of(commenter));
        when(commentRepository.findCommentWithPost(post.getPostId(), reply.getCommentId())).thenReturn(Optional.of(reply));

        CommentResponseDTO response = commentService.modifyComment(post.getPostId(), reply.getCommentId(), commenter.getUserId(), modifyRequestDTO);
        assertThat(response.getComment().getCommentBody()).isEqualTo("modified comment");
        assertThat(response.getComment().getParentCommentId()).isEqualTo(originalParent.getCommentId());
        assertThat(reply.isModified()).isTrue();
        assertThat(reply.getParentComment()).isSameAs(originalParent);
        verify(commentRepository, never()).findCommentWithPost(post.getPostId(), modifyRequestDTO.getParentCommentId());
    }
    @Test
    @DisplayName("댓글 작성자가 아니면 수정 요청 시 403")
    void modifyComment_notOwner_throwsForbiddenException(){
        when(postRepository.findById(anyLong())).thenReturn(Optional.of(post));
        when(commentRepository.findCommentWithPost(anyLong(), anyLong())).thenReturn(Optional.of(comment));
        doThrow(new ForbiddenException()).when(authValidator).validateOwner(author.getUserId(), commenter.getUserId());

        assertThatThrownBy(()->commentService.modifyComment(1L, 1L, 1L, modifyRequestDTO)).isInstanceOf(ForbiddenException.class);
        assertThat(comment.isModified()).isFalse();
    }

    @Test
    @DisplayName("댓글 수정 시 게시글이 존재하지 않으면 404")
    void modifyComment_postNotFound_throwsContentNotFoundException() {
        when(postRepository.findById(post.getPostId())).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                commentService.modifyComment(
                        post.getPostId(),
                        comment.getCommentId(),
                        commenter.getUserId(),
                        modifyRequestDTO
                )
        ).isInstanceOf(ContentNotFoundException.class);

        verify(commentRepository, never()).findCommentWithPost(anyLong(), anyLong());
        verify(authValidator, never()).validateOwner(anyLong(), anyLong());
    }

    @Test
    @DisplayName("댓글 수정 시 댓글이 존재하지 않으면 404")
    void modifyComment_commentNotFound_throwsContentNotFoundException() {
        when(postRepository.findById(post.getPostId())).thenReturn(Optional.of(post));
        when(commentRepository.findCommentWithPost(post.getPostId(), comment.getCommentId())).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                commentService.modifyComment(
                        post.getPostId(),
                        comment.getCommentId(),
                        commenter.getUserId(),
                        modifyRequestDTO
                )
        ).isInstanceOf(ContentNotFoundException.class);

        verify(authValidator, never()).validateOwner(anyLong(), anyLong());
    }

    @Test
    @DisplayName("댓글 삭제 성공")
    void deleteComment_success(){
        when(commentRepository.findCommentWithPost(anyLong(), anyLong())).thenReturn(Optional.of(comment));

        CommentRemoveResponseDTO response = commentService.deleteComment(1L, 1L, 2L);
        assertThat(response.isDeleted()).isTrue();
        assertThat(comment.isDeleted()).isTrue();
    }
    @Test
    @DisplayName("댓글 작성자가 아니면 삭제 요청 시 403")
    void deleteComment_notOwner_throwsForbiddenException(){
        when(commentRepository.findCommentWithPost(anyLong(), anyLong())).thenReturn(Optional.of(comment));
        doThrow(new ForbiddenException()).when(authValidator).validateOwner(author.getUserId(), commenter.getUserId());

        assertThatThrownBy(()->commentService.deleteComment(1L, 1L, 1L)).isInstanceOf(ForbiddenException.class);
        assertThat(comment.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("댓글 삭제 시 댓글이 존재하지 않으면 404")
    void deleteComment_commentNotFound_throwsContentNotFoundException() {
        when(commentRepository.findCommentWithPost(post.getPostId(), comment.getCommentId())).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                commentService.deleteComment(
                        post.getPostId(),
                        comment.getCommentId(),
                        commenter.getUserId()
                )
        ).isInstanceOf(ContentNotFoundException.class);

        verify(authValidator, never()).validateOwner(anyLong(), anyLong());
    }

    private CommentRequestDTO commentRequest(String commentBody, Long parentCommentId) {
        CommentRequestDTO requestDTO = new CommentRequestDTO();
        requestDTO.setCommentBody(commentBody);
        requestDTO.setParentCommentId(parentCommentId);
        return requestDTO;
    }
}
