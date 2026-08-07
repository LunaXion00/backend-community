package com.example.community.comment.controller;

import com.example.community.comment.dto.CommentDTO;
import com.example.community.comment.dto.CommentRemoveResponseDTO;
import com.example.community.comment.dto.CommentRequestDTO;
import com.example.community.comment.dto.CommentResponseDTO;
import com.example.community.comment.service.CommentService;
import com.example.community.global.security.jwt.JwtTokenProvider;
import com.example.community.global.security.config.SecurityConfig;
import com.example.community.global.security.filter.JwtFilter;
import com.example.community.global.dto.AuthorDTO;
import com.example.community.global.exceptions.ContentNotFoundException;
import com.example.community.global.exceptions.ForbiddenException;
import com.example.community.global.exceptions.InvalidInputException;
import com.example.community.user.entity.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CommentController.class)
@Import({SecurityConfig.class, JwtFilter.class})
public class CommentControllerTest {
    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CommentService commentService;
    @MockitoBean
    JwtTokenProvider jwtTokenProvider;

    Authentication authentication;
    CommentResponseDTO responseDTO = new CommentResponseDTO(
            new AuthorDTO(2L, UserStatus.ACTIVE, "commenter", ""),
            new CommentDTO(
                    1L,
                    null,
                    "test comment",
                    LocalDateTime.of(2026, 7, 7, 12, 0),
                    false,
                    null,
                    false,
                    null
            )
    );

    @BeforeEach
    void setUp(){
        authentication = new UsernamePasswordAuthenticationToken(
                "1",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
    }
    @Test
    @DisplayName("댓글 작성 성공 시 201")
    void uploadComment_withAuthenticate() throws Exception {
        when(commentService.uploadComment(anyLong(), anyLong(), any(CommentRequestDTO.class))).thenReturn(responseDTO);
        mockMvc.perform(post("/api/posts/1/comments").with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "commentBody": "test comment"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("comment_create_success"))
                .andExpect(jsonPath("$.data.author.nickname").value("commenter"))
                .andExpect(jsonPath("$.data.comment.commentId").value(1L))
                .andExpect(jsonPath("$.data.comment.parentCommentId").value(nullValue()))
                .andExpect(jsonPath("$.data.comment.commentBody").value("test comment"))
                .andExpect(jsonPath("$.data.comment.modified").value(false))
                .andExpect(jsonPath("$.data.comment.deleted").value(false));
        verify(commentService).uploadComment(eq(1L), eq(1L), argThat(request ->
                request.getCommentBody().equals("test comment") && request.getParentCommentId() == null
        ));
    }

    @Test
    @DisplayName("대댓글 작성 요청과 응답에 직접 부모 ID를 사용한다.")
    void uploadReplyComment_withParentCommentId() throws Exception {
        CommentResponseDTO replyResponse = new CommentResponseDTO(
                new AuthorDTO(2L, UserStatus.ACTIVE, "commenter", ""),
                new CommentDTO(
                        2L,
                        1L,
                        "reply",
                        LocalDateTime.of(2026, 7, 7, 12, 1),
                        false,
                        null,
                        false,
                        null
                )
        );
        when(commentService.uploadComment(anyLong(), anyLong(), any(CommentRequestDTO.class))).thenReturn(replyResponse);

        mockMvc.perform(post("/api/posts/1/comments").with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "commentBody": "reply",
                                    "parentCommentId": 1
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("comment_create_success"))
                .andExpect(jsonPath("$.data.comment.commentId").value(2L))
                .andExpect(jsonPath("$.data.comment.parentCommentId").value(1L));

        verify(commentService).uploadComment(eq(1L), eq(1L), argThat(request ->
                request.getCommentBody().equals("reply") && request.getParentCommentId().equals(1L)
        ));
    }
    @Test
    @DisplayName("빈 댓글은 400")
    void uploadComment_emptyBody_returns400() throws Exception {
        mockMvc.perform(post("/api/posts/1/comments")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "commentBody": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_input"));
    }

    @Test
    @DisplayName("댓글 생성시 토큰이 유효하지 않으면 401")
    void uploadComment_invalidToken_returns401() throws Exception {
        mockMvc.perform(post("/api/posts/1/comments")
                        .header("Authorization", "Bearer invalid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "commentBody": "invalid token"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("댓글 생성시 게시글이 조회되지 않으면 404")
    void uploadComment_invalidPost_returns404() throws Exception {
        doThrow(new ContentNotFoundException()).when(commentService).uploadComment(eq(1L), eq(1L), any(CommentRequestDTO.class));
        mockMvc.perform(post("/api/posts/1/comments")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "commentBody": "invalid token"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("content_not_found"));
    }

    @Test
    @DisplayName("삭제된 부모 댓글에 답글을 작성하면 400")
    void uploadComment_deletedParent_returns400() throws Exception {
        doThrow(new InvalidInputException()).when(commentService).uploadComment(eq(1L), eq(1L), any(CommentRequestDTO.class));

        mockMvc.perform(post("/api/posts/1/comments")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "commentBody": "reply",
                                    "parentCommentId": 2
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_input"));
    }

    @Test
    @DisplayName("댓글 목록 조회 성공 시 200")
    void getComments_success_returns200() throws Exception {
        when(commentService.getComments(1L)).thenReturn(List.of(responseDTO));

        mockMvc.perform(get("/api/posts/1/comments")
                        .with(authentication(authentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("comments_get_success"))
                .andExpect(jsonPath("$.data[0].author.userId").value(2L))
                .andExpect(jsonPath("$.data[0].author.nickname").value("commenter"))
                .andExpect(jsonPath("$.data[0].comment.commentId").value(1L))
                .andExpect(jsonPath("$.data[0].comment.parentCommentId").value(nullValue()))
                .andExpect(jsonPath("$.data[0].comment.commentBody").value("test comment"))
                .andExpect(jsonPath("$.data[0].comment.modified").value(false))
                .andExpect(jsonPath("$.data[0].comment.deleted").value(false));

        verify(commentService).getComments(1L);
    }

    @Test
    @DisplayName("댓글 조회 시 토큰이 유효하지 않으면 401")
    void getComments_invalidToken_returns401() throws Exception {
         mockMvc.perform(get("/api/posts/1/comments")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("댓글 조회 시 게시글이 존재하지 않으면 404")
    void getComments_invalidPost_returns404() throws Exception {
        doThrow(new ContentNotFoundException()).when(commentService).getComments(1L);

        mockMvc.perform(get("/api/posts/1/comments")
                        .with(authentication(authentication)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("content_not_found"));

        verify(commentService).getComments(1L);
    }

    @Test
    @DisplayName("댓글 수정 성공 시 200")
    void modifyComment_success_returns200() throws Exception {
        CommentResponseDTO responseDTO = new CommentResponseDTO(
                new AuthorDTO(2L, UserStatus.ACTIVE, "commenter", ""),
                new CommentDTO(
                        1L,
                        null,
                        "modified comment",
                        LocalDateTime.of(2026, 7, 7, 12, 0),
                        true,
                        LocalDateTime.of(2026, 7, 7, 12, 10),
                        false,
                        null
                )
        );

        when(commentService.modifyComment(eq(1L), eq(1L), eq(1L), any(CommentRequestDTO.class))).thenReturn(responseDTO);

        mockMvc.perform(patch("/api/posts/1/comments/1")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "commentBody": "modified comment"
                        }
                    """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("comment_modify_success"))
                .andExpect(jsonPath("$.data.comment.commentBody").value("modified comment"))
                .andExpect(jsonPath("$.data.comment.modified").value(true));

        verify(commentService).modifyComment(eq(1L), eq(1L), eq(1L), argThat(request -> request.getCommentBody().equals("modified comment")));
    }

    @Test
    @DisplayName("댓글 수정 요청 시 토큰이 유효하지 않으면 401")
    void modifyComment_invalidToken_returns401() throws Exception{
        mockMvc.perform(patch("/api/posts/1/comments/1")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
    }
    @Test
    @DisplayName("댓글 수정 요청자와 작성자가 다르면 403")
    void modifyComment_notOwner_returns403() throws Exception {
        doThrow(new ForbiddenException()).when(commentService).modifyComment(eq(1L), eq(1L), eq(2L), any(CommentRequestDTO.class));

        Authentication otherUser = new UsernamePasswordAuthenticationToken(
                "2",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        mockMvc.perform(patch("/api/posts/1/comments/1")
                .with(authentication(otherUser))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "commentBody":"modify comment"
                        }
                        """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("access_denied"));
    }

    @Test
    @DisplayName("댓글 삭제 성공")
    void deleteComment_success_returns200() throws Exception {
        when(commentService.deleteComment(1L, 1L, 1L)).thenReturn(new CommentRemoveResponseDTO(1L, true, LocalDateTime.of(2026, 7, 7, 13, 0)));
        mockMvc.perform(delete("/api/posts/1/comments/1")
                .with(authentication(authentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("comment_delete_success"))
                .andExpect(jsonPath("$.data.deleted").value(true));
        verify(commentService).deleteComment(1L, 1L, 1L);
    }

    @Test
    @DisplayName("댓글 삭제 시 토큰이 유효하지 않으면 401")
    void deleteComment_invalidToken_returns401() throws Exception{
        mockMvc.perform(delete("/api/posts/1/comments/1")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("댓글 삭제 시 본인이 아닌 경우 403")
    void deleteComment_notOwner_returns403() throws Exception{
        doThrow(new ForbiddenException()).when(commentService).deleteComment(eq(1L), eq(1L), eq(2L));
        Authentication otherUser = new UsernamePasswordAuthenticationToken(
                "2",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        mockMvc.perform(delete("/api/posts/1/comments/1")
                .with(authentication(otherUser)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("access_denied"));
    }
}
