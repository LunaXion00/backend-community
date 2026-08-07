package com.example.community.post.controller;

import com.example.community.auth.session.RefreshSessionStore;
import com.example.community.global.security.jwt.JwtTokenProvider;
import com.example.community.global.security.config.SecurityConfig;
import com.example.community.global.security.filter.JwtFilter;
import com.example.community.global.dto.AuthorDTO;
import com.example.community.global.exceptions.AlreadyReportedException;
import com.example.community.global.exceptions.ConflictException;
import com.example.community.global.exceptions.ContentNotFoundException;
import com.example.community.global.exceptions.ForbiddenException;
import com.example.community.post.dto.*;
import com.example.community.post.entity.Post;
import com.example.community.post.entity.ReportReason;
import com.example.community.post.service.PostService;
import com.example.community.user.entity.User;
import com.example.community.user.entity.UserRole;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PostController.class)
@Import({SecurityConfig.class, JwtFilter.class})
class PostControllerTest {
    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    PostService postService;
    @MockitoBean
    JwtTokenProvider jwtTokenProvider;
    @MockitoBean
    RefreshSessionStore refreshSessionStore;

    Authentication userAuthentication;
    Authentication adminAuthentication;

    User user;
    Post post;
    AuthorDTO authorDTO;
    PostResponseDTO postResponseDTO;
    PostDetailResponseDTO postDetailResponseDTO;
    PostListResponseDTO postListResponseDTO;
    PostPageResponseDTO postPageResponseDTO;

    @BeforeEach
    void setUp() {
        userAuthentication = new UsernamePasswordAuthenticationToken(
                "1",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        adminAuthentication = new UsernamePasswordAuthenticationToken(
                "99",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );

        user = new User(1L, "tester", "", UserRole.ROLE_USER, UserStatus.ACTIVE);

        post = new Post(user, "title", "body", "");
        ReflectionTestUtils.setField(post, "postId", 1L);

        authorDTO = new AuthorDTO(1L, UserStatus.ACTIVE, "tester", "");

        postResponseDTO = new PostResponseDTO(authorDTO, new PostDTO(post));
        postDetailResponseDTO = new PostDetailResponseDTO(
                authorDTO,
                new PostDTO(post),
                new MetaDTO(0, 0, 0, false)
        );
        postListResponseDTO = new PostListResponseDTO(authorDTO, new PostItemDTO(post));
        postPageResponseDTO = new PostPageResponseDTO(List.of(postListResponseDTO), 0, 20, 1, 1);
    }

    @Test
    @DisplayName("게시글 작성 성공 201")
    void uploadPost_success_returns201() throws Exception{
        when(postService.upload(eq(1L), any(PostRequestDTO.class))).thenReturn(postResponseDTO);

        mockMvc.perform(post("/api/posts")
                        .with(authentication(userAuthentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "title": "title",
                              "postBody": "body",
                              "postImageUrl": ""
                            }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("post_create_success"))
                .andExpect(jsonPath("$.data.author.nickname").value("tester"))
                .andExpect(jsonPath("$.data.post.postId").value(1L))
                .andExpect(jsonPath("$.data.post.title").value("title"))
                .andExpect(jsonPath("$.data.post.postBody").value("body"));

        verify(postService).upload(eq(1L), argThat(request -> request.getTitle().equals("title") && request.getPostBody().equals("body")));
    }
    @Test
    @DisplayName("빈 게시글 제목은 400")
    void uploadPost_emptyTitle_returns400() throws Exception{
        mockMvc.perform(post("/api/posts")
                .with(authentication(userAuthentication))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "title":"",
                            "postBody": "body",
                            "postImageUrl":""
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_input"));
    }
    @Test
    @DisplayName("빈 게시글 본문은 400")
    void uploadPost_emptyBody_returns400() throws Exception{
        mockMvc.perform(post("/api/posts")
                        .with(authentication(userAuthentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "title":"title",
                            "postBody": "",
                            "postImageUrl":""
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_input"));
    }
    @Test
    @DisplayName("게시글 작성시 토큰이 유효하지 않으면 401")
    void uploadPost_invalidToken_returns401() throws Exception{
        mockMvc.perform(post("/api/posts")
                        .header("Authorization", "Bearer Invalid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                            "title":"title",
                            "postBody": "body",
                            "postImageUrl":""
                        }
                        """))
                .andExpect(status().isUnauthorized());
    }
    @Test
    @DisplayName("게시글 목록 조회 성공 시 200")
    void getPostList_success_returns200() throws Exception{
        when(postService.getPostList(0)).thenReturn(postPageResponseDTO);

        mockMvc.perform(get("/api/posts")
                        .with(authentication(userAuthentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("posts_loading_success"))
                .andExpect(jsonPath("$.data.posts[0].post.postId").value(1L))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1));

        verify(postService).getPostList(0);
    }

    @Test
    @DisplayName("게시글 목록의 page 요청값을 service에 전달한다")
    void getPostList_pageParameter_callsService() throws Exception {
        PostPageResponseDTO secondPage = new PostPageResponseDTO(List.of(), 1, 20, 21, 2);
        when(postService.getPostList(1)).thenReturn(secondPage);

        mockMvc.perform(get("/api/posts")
                        .param("page", "1")
                        .with(authentication(userAuthentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.posts").isEmpty())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(21))
                .andExpect(jsonPath("$.data.totalPages").value(2));

        verify(postService).getPostList(1);
    }

    @Test
    @DisplayName("게시글 목록의 page가 음수이면 400")
    void getPostList_negativePage_returns400() throws Exception {
        mockMvc.perform(get("/api/posts")
                        .param("page", "-1")
                        .with(authentication(userAuthentication)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_input"));

        verifyNoInteractions(postService);
    }
    @Test
    @DisplayName("게시글 상세 조회 성공 시 200")
    void getPostDetail_success_returns200() throws Exception {
        when(postService.getPostDetail(1L, UserRole.ROLE_USER, 1L)).thenReturn(postDetailResponseDTO);

        mockMvc.perform(get("/api/posts/1")
                        .with(authentication(userAuthentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("post_loading_success"))
                .andExpect(jsonPath("$.data.author.userId").value(1L))
                .andExpect(jsonPath("$.data.author.nickname").value("tester"))
                .andExpect(jsonPath("$.data.post.postId").value(1L))
                .andExpect(jsonPath("$.data.post.title").value("title"));

        verify(postService).getPostDetail(1L, UserRole.ROLE_USER, 1L);
    }

    @Test
    @DisplayName("관리자는 게시글 상세 조회 시 ROLE_ADMIN으로 service를 호출한다")
    void getPostDetail_admin_callsServiceWithAdminRole() throws Exception {
        when(postService.getPostDetail(99L, UserRole.ROLE_ADMIN, 1L)).thenReturn(postDetailResponseDTO);

        mockMvc.perform(get("/api/posts/1")
                        .with(authentication(adminAuthentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("post_loading_success"));

        verify(postService).getPostDetail(99L, UserRole.ROLE_ADMIN, 1L);
    }

    @Test
    @DisplayName("게시글 상세 조회 권한이 없으면 403")
    void getPostDetail_forbidden_returns403() throws Exception {
        when(postService.getPostDetail(1L, UserRole.ROLE_USER, 1L)).thenThrow(new ForbiddenException());

        mockMvc.perform(get("/api/posts/1")
                        .with(authentication(userAuthentication)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("access_denied"));
    }

    @Test
    @DisplayName("게시글 상세 조회 시 게시글이 없으면 404")
    void getPostDetail_notFound_returns404() throws Exception {
        when(postService.getPostDetail(1L, UserRole.ROLE_USER, 1L)).thenThrow(new ContentNotFoundException());

        mockMvc.perform(get("/api/posts/1")
                        .with(authentication(userAuthentication)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("content_not_found"));
    }

    @Test
    @DisplayName("게시글 수정 성공 시 200")
    void modifyPost_success_returns200() throws Exception {
        Post modifiedPost = new Post(user, "new title", "new body", "");
        ReflectionTestUtils.setField(modifiedPost, "postId", 1L);
        modifiedPost.modifyPost("new title", "new body", "");

        PostDetailResponseDTO responseDTO = new PostDetailResponseDTO(
                authorDTO,
                new PostDTO(modifiedPost),
                new MetaDTO(0, 0, 0, false)
        );

        when(postService.modifyPost(eq(1L), eq(1L), any(PostRequestDTO.class))).thenReturn(responseDTO);

        mockMvc.perform(patch("/api/posts/1")
                        .with(authentication(userAuthentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "title": "new title",
                              "postBody": "new body",
                              "postImageUrl": ""
                            }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("post_modify_success"))
                .andExpect(jsonPath("$.data.post.title").value("new title"))
                .andExpect(jsonPath("$.data.post.postBody").value("new body"));

        verify(postService).modifyPost(eq(1L), eq(1L), argThat(request -> request.getTitle().equals("new title") && request.getPostBody().equals("new body")));
    }

    @Test
    @DisplayName("게시글 수정 요청자와 작성자가 다르면 403")
    void modifyPost_notOwner_returns403() throws Exception {
        doThrow(new ForbiddenException()).when(postService).modifyPost(eq(2L), eq(1L), any(PostRequestDTO.class));

        Authentication otherUser = new UsernamePasswordAuthenticationToken(
                "2",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        mockMvc.perform(patch("/api/posts/1")
                        .with(authentication(otherUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "title": "new title",
                              "postBody": "new body",
                              "postImageUrl": ""
                            }
                        """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("access_denied"));
    }

    @Test
    @DisplayName("게시글 삭제 성공 시 200")
    void deletePost_success_returns200() throws Exception {
        mockMvc.perform(delete("/api/posts/1")
                        .with(authentication(userAuthentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("post_delete_success"));

        verify(postService).deletePost(1L, 1L);
    }

    @Test
    @DisplayName("게시글 좋아요 성공 시 200")
    void likePost_success_returns200() throws Exception {
        when(postService.likePost(1L, 1L)).thenReturn(new LikeResponseDTO(1L, 1, true));

        mockMvc.perform(post("/api/posts/1/likes")
                        .with(authentication(userAuthentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("post_like_success"))
                .andExpect(jsonPath("$.data.postId").value(1L))
                .andExpect(jsonPath("$.data.likes").value(1))
                .andExpect(jsonPath("$.data.liked").value(true));

        verify(postService).likePost(1L, 1L);
    }

    @Test
    @DisplayName("이미 좋아요한 게시글이면 409")
    void likePost_conflict_returns409() throws Exception {
        when(postService.likePost(1L, 1L)).thenThrow(new ConflictException());

        mockMvc.perform(post("/api/posts/1/likes")
                        .with(authentication(userAuthentication)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("state_conflict"));
    }

    @Test
    @DisplayName("게시글 좋아요 취소 성공 시 200")
    void unlikePost_success_returns200() throws Exception {
        when(postService.unlikePost(1L, 1L)).thenReturn(new LikeResponseDTO(1L, 0, false));

        mockMvc.perform(delete("/api/posts/1/likes")
                        .with(authentication(userAuthentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("post_unlike_success"))
                .andExpect(jsonPath("$.data.postId").value(1L))
                .andExpect(jsonPath("$.data.likes").value(0))
                .andExpect(jsonPath("$.data.liked").value(false));

        verify(postService).unlikePost(1L, 1L);
    }

    @Test
    @DisplayName("게시글 신고 성공 시 201")
    void reportPost_success_returns201() throws Exception {
        when(postService.reportPost(eq(1L), eq(1L), any(ReportRequestDTO.class))).thenReturn(new ReportResponseDTO(1L, 1L, true));

        mockMvc.perform(post("/api/posts/1/report")
                        .with(authentication(userAuthentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "reason": "SPAM",
                              "description": "spam post"
                            }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("post_report_success"))
                .andExpect(jsonPath("$.data.postId").value(1L))
                .andExpect(jsonPath("$.data.reportId").value(1L))
                .andExpect(jsonPath("$.data.blinded").value(true));

        verify(postService).reportPost(eq(1L), eq(1L), argThat(request -> request.getReason() == ReportReason.SPAM && request.getDescription().equals("spam post")));
    }

    @Test
    @DisplayName("신고 사유가 없으면 400")
    void reportPost_invalidInput_returns400() throws Exception {
        mockMvc.perform(post("/api/posts/1/report")
                        .with(authentication(userAuthentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "description": "missing reason"
                            }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_input"));

        verify(postService, never()).reportPost(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("이미 신고한 게시글이면 409")
    void reportPost_alreadyReported_returns409() throws Exception {
        when(postService.reportPost(eq(1L), eq(1L), any(ReportRequestDTO.class))).thenThrow(new AlreadyReportedException());

        mockMvc.perform(post("/api/posts/1/report")
                        .with(authentication(userAuthentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "reason": "SPAM",
                              "description": "spam post"
                            }
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("already_reported"));
    }

    @Test
    @DisplayName("인증 정보가 없으면 401")
    void request_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(get("/api/posts"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(postService);
    }
}
