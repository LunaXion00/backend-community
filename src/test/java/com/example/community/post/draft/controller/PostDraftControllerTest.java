package com.example.community.post.draft.controller;

import com.example.community.global.security.jwt.JwtTokenProvider;
import com.example.community.global.security.config.SecurityConfig;
import com.example.community.global.security.filter.JwtFilter;
import com.example.community.global.dto.AuthorDTO;
import com.example.community.global.exceptions.ConflictException;
import com.example.community.global.exceptions.ContentNotFoundException;
import com.example.community.post.draft.dto.PostDraftRequestDTO;
import com.example.community.post.draft.dto.PostDraftResponseDTO;
import com.example.community.post.draft.service.PostDraftService;
import com.example.community.post.dto.PostDTO;
import com.example.community.post.dto.PostResponseDTO;
import com.example.community.post.entity.Post;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PostDraftController.class)
@Import({SecurityConfig.class, JwtFilter.class})
public class PostDraftControllerTest {
    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PostDraftService postDraftService;
    @MockitoBean
    JwtTokenProvider jwtTokenProvider;

    Authentication authentication;
    PostDraftResponseDTO draftResponseDTO;

    @BeforeEach
    void setUp(){
        authentication = new UsernamePasswordAuthenticationToken(
                "1",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        draftResponseDTO = new PostDraftResponseDTO(
                1L,
                "draft title",
                "draft body",
                "",
                LocalDateTime.of(2026, 7, 8, 12, 0),
                1
        );
    }
    @Test
    @DisplayName("현재 임시저장 글이 있으면 200")
    void getCurrentDraft_found_returns200() throws Exception {
        when(postDraftService.getCurrentDraft(1L)).thenReturn(Optional.of(draftResponseDTO));

        mockMvc.perform(get("/api/draft-post/current").with(authentication(authentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("draft_found"))
                .andExpect(jsonPath("$.data.draftId").value(1L))
                .andExpect(jsonPath("$.data.title").value("draft title"))
                .andExpect(jsonPath("$.data.postBody").value("draft body"))
                .andExpect(jsonPath("$.data.version").value(1));

        verify(postDraftService).getCurrentDraft(1L);
    }

    @Test
    @DisplayName("임시저장 글 생성 성공 시 201")
    void saveDraft_success_returns201() throws Exception {
        when(postDraftService.saveDraft(eq(1L), any(PostDraftRequestDTO.class))).thenReturn(draftResponseDTO);

        mockMvc.perform(post("/api/draft-post")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "title": "draft title",
                              "postBody": "draft body",
                              "postImageUrl": ""
                            }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("draft_post_success"))
                .andExpect(jsonPath("$.data.title").value("draft title"))
                .andExpect(jsonPath("$.data.postBody").value("draft body"));

        verify(postDraftService).saveDraft(eq(1L), argThat(request -> request.getTitle().equals("draft title") && request.getPostBody().equals("draft body")));
    }

    @Test
    @DisplayName("현재 임시저장 글이 없으면 204")
    void getCurrentDraft_notFound_returns204() throws Exception {
        when(postDraftService.getCurrentDraft(1L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/draft-post/current")
                        .with(authentication(authentication)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(postDraftService).getCurrentDraft(1L);
    }

    @Test
    @DisplayName("임시저장 글 생성 시 입력값이 비어있으면 400")
    void saveDraft_invalidInput_returns400() throws Exception {
        mockMvc.perform(post("/api/draft-post")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "title": "",
                              "postBody": "",
                              "postImageUrl": ""
                            }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_input"));

        verifyNoInteractions(postDraftService);
    }

    @Test
    @DisplayName("이미 임시저장 글이 있으면 409")
    void saveDraft_alreadyExists_returns409() throws Exception {
        when(postDraftService.saveDraft(eq(1L), any(PostDraftRequestDTO.class))).thenThrow(new ConflictException());

        mockMvc.perform(post("/api/draft-post")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "title": "draft title",
                              "postBody": "draft body",
                              "postImageUrl": ""
                            }
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("state_conflict"));
    }
    @Test
    @DisplayName("임시저장 글 덮어쓰기 성공 시 200")
    void overwriteDraft_success_returns200() throws Exception {
        PostDraftResponseDTO updatedResponse = new PostDraftResponseDTO(
                1L,
                "updated title",
                "updated body",
                "updated.png",
                LocalDateTime.of(2026, 7, 8, 13, 0),
                2
        );

        when(postDraftService.overwriteDraft(eq(1L), any(PostDraftRequestDTO.class))).thenReturn(updatedResponse);

        mockMvc.perform(patch("/api/draft-post")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "title": "updated title",
                              "postBody": "updated body",
                              "postImageUrl": "updated.png",
                              "version": 1
                            }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("draft_overwrite_success"))
                .andExpect(jsonPath("$.data.title").value("updated title"))
                .andExpect(jsonPath("$.data.postBody").value("updated body"))
                .andExpect(jsonPath("$.data.version").value(2));

        verify(postDraftService).overwriteDraft(eq(1L), any(PostDraftRequestDTO.class));
    }

    @Test
    @DisplayName("덮어쓸 임시저장 글이 없으면 404")
    void overwriteDraft_notFound_returns404() throws Exception {
        when(postDraftService.overwriteDraft(eq(1L), any(PostDraftRequestDTO.class))).thenThrow(new ContentNotFoundException());

        mockMvc.perform(patch("/api/draft-post")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "title": "updated title",
                              "postBody": "updated body",
                              "postImageUrl": ""
                            }
                        """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("content_not_found"));
    }

    @Test
    @DisplayName("임시저장 글 발행 성공 시 201")
    void publishDraft_success_returns201() throws Exception {
        User author = new User(1L, "author", "", UserRole.ROLE_USER, UserStatus.ACTIVE);
        Post post = new Post(author, "draft title", "draft body", "");
        ReflectionTestUtils.setField(post, "postId", 1L);

        PostResponseDTO postResponseDTO = new PostResponseDTO(
                new AuthorDTO(1L, UserStatus.ACTIVE, "author", ""),
                new PostDTO(post)
        );

        when(postDraftService.publishDraft(eq(1L), any(PostDraftRequestDTO.class))).thenReturn(postResponseDTO);

        mockMvc.perform(post("/api/draft-post/publish")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "title": "draft title",
                              "postBody": "draft body",
                              "postImageUrl": ""
                            }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("draft_published"))
                .andExpect(jsonPath("$.data.author.nickname").value("author"))
                .andExpect(jsonPath("$.data.post.postId").value(1L))
                .andExpect(jsonPath("$.data.post.title").value("draft title"));

        verify(postDraftService).publishDraft(eq(1L), any(PostDraftRequestDTO.class));
    }

    @Test
    @DisplayName("임시저장 글 삭제 성공 시 200")
    void deleteDraft_success_returns200() throws Exception {
        mockMvc.perform(delete("/api/draft-post")
                        .with(authentication(authentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("draft_delete_success"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(postDraftService).deleteDraft(1L);
    }

    @Test
    @DisplayName("인증 정보가 없으면 401")
    void request_withoutAuthentication_returns401() throws Exception {
        mockMvc.perform(get("/api/draft-post/current")).andExpect(status().isUnauthorized());

        verifyNoInteractions(postDraftService);
    }

    @Test
    @DisplayName("삭제할 임시저장 글이 없으면 404")
    void deleteDraft_notFound_returns404() throws Exception {
        doThrow(new ContentNotFoundException()).when(postDraftService).deleteDraft(1L);

        mockMvc.perform(delete("/api/draft-post")
                        .with(authentication(authentication)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("content_not_found"));
    }

}
