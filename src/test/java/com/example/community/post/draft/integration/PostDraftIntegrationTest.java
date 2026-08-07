package com.example.community.post.draft.integration;

import com.example.community.post.draft.entity.PostDraft;
import com.example.community.post.draft.repository.PostDraftRepository;
import com.example.community.post.entity.Post;
import com.example.community.post.repository.PostRepository;
import com.example.community.user.entity.User;
import com.example.community.user.entity.UserCredential;
import com.example.community.user.entity.UserRole;
import com.example.community.user.entity.UserStatus;
import com.example.community.user.repository.UserCredentialRepository;
import com.example.community.user.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class PostDraftIntegrationTest {
    @Autowired
    MockMvc mockMvc;

    @Autowired
    PostDraftRepository postDraftRepository;
    @Autowired
    PostRepository postRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    UserCredentialRepository userCredentialRepository;
    @Autowired
    PasswordEncoder passwordEncoder;


    User user;
    User otherUser;

    @BeforeEach
    void setUp(){
        postDraftRepository.deleteAll();
        postRepository.deleteAll();
        userCredentialRepository.deleteAll();
        userRepository.deleteAll();

        user = userRepository.save(new User("user", "", UserRole.ROLE_USER, UserStatus.ACTIVE));
        otherUser = userRepository.save(new User("other", "", UserRole.ROLE_USER, UserStatus.ACTIVE));

        userCredentialRepository.save(new UserCredential(user, "user@test.com", passwordEncoder.encode("Test1234!")));
        userCredentialRepository.save(new UserCredential(otherUser, "other@test.com", passwordEncoder.encode("Test1234!")));
    }

    @Test
    @DisplayName("임시저장글 최초 등록 확인")
    void uploadDraft_success() throws Exception{
        String accessToken = loginAndGetAccessToken("user@test.com");
        mockMvc.perform(post("/api/draft-post")
                        .header("Authorization", "Bearer " + accessToken)
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
                .andExpect(jsonPath("$.data.postBody").value("draft body"))
                .andExpect(jsonPath("$.data.version").value(1));

        PostDraft savedDraft = postDraftRepository.findByAuthorUserId(user.getUserId()).orElseThrow();

        assertThat(savedDraft.getTitle()).isEqualTo("draft title");
        assertThat(savedDraft.getPostBody()).isEqualTo("draft body");
        assertThat(savedDraft.getAuthor().getUserId()).isEqualTo(user.getUserId());
        assertThat(savedDraft.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("JWT 인증 후 현재 임시저장 글을 조회할 수 있다")
    void getCurrentDraft_found() throws Exception {
        PostDraft draft = saveDraft(user, "draft title", "draft body", "");
        String accessToken = loginAndGetAccessToken("user@test.com");

        mockMvc.perform(get("/api/draft-post/current")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("draft_found"))
                .andExpect(jsonPath("$.data.draftId").value(draft.getDraftId()))
                .andExpect(jsonPath("$.data.title").value("draft title"))
                .andExpect(jsonPath("$.data.postBody").value("draft body"))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    @DisplayName("현재 임시저장 글이 없으면 204")
    void getCurrentDraft_notFound() throws Exception {
        String accessToken = loginAndGetAccessToken("user@test.com");

        mockMvc.perform(get("/api/draft-post/current")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("JWT 인증 후 임시저장 글을 덮어쓸 수 있다")
    void overwriteDraft_success() throws Exception {
        PostDraft draft = saveDraft(user, "draft title", "draft body", "");
        String accessToken = loginAndGetAccessToken("user@test.com");

        mockMvc.perform(patch("/api/draft-post")
                        .header("Authorization", "Bearer " + accessToken)
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
                .andExpect(jsonPath("$.data.postImageUrl").value("updated.png"))
                .andExpect(jsonPath("$.data.version").value(2));

        PostDraft updatedDraft = postDraftRepository.findById(draft.getDraftId()).orElseThrow();

        assertThat(updatedDraft.getTitle()).isEqualTo("updated title");
        assertThat(updatedDraft.getPostBody()).isEqualTo("updated body");
        assertThat(updatedDraft.getPostImageUrl()).isEqualTo("updated.png");
        assertThat(updatedDraft.getVersion()).isEqualTo(2);
        assertThat(updatedDraft.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("JWT 인증 후 임시저장 글을 발행하면 게시글이 생성되고 임시글은 삭제된다")
    void publishDraft_success() throws Exception {
        PostDraft draft = saveDraft(user, "draft title", "draft body", "");
        String accessToken = loginAndGetAccessToken("user@test.com");

        mockMvc.perform(post("/api/draft-post/publish")
                        .header("Authorization", "Bearer " + accessToken)
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
                .andExpect(jsonPath("$.data.author.nickname").value("user"))
                .andExpect(jsonPath("$.data.post.title").value("draft title"))
                .andExpect(jsonPath("$.data.post.postBody").value("draft body"));

        Optional<PostDraft> deletedDraft = postDraftRepository.findById(draft.getDraftId());

        assertThat(deletedDraft).isEmpty();

        Post savedPost = postRepository.findAll().get(0);

        assertThat(savedPost.getAuthor().getUserId()).isEqualTo(user.getUserId());
        assertThat(savedPost.getTitle()).isEqualTo("draft title");
        assertThat(savedPost.getDetail().getPostBody()).isEqualTo("draft body");
    }

    @Test
    @DisplayName("JWT 인증 후 임시저장 글을 삭제할 수 있다")
    void deleteDraft_success() throws Exception {
        PostDraft draft = saveDraft(user, "draft title", "draft body", "");
        String accessToken = loginAndGetAccessToken("user@test.com");

        mockMvc.perform(delete("/api/draft-post")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("draft_delete_success"));

        assertThat(postDraftRepository.findById(draft.getDraftId())).isEmpty();
    }

    @Test
    @DisplayName("다른 사용자는 내 임시저장 글을 덮어쓸 수 없다")
    void overwriteDraft_otherUser_returns404AndOriginalDraftIsKept() throws Exception {
        PostDraft draft = saveDraft(user, "draft title", "draft body", "");
        String otherAccessToken = loginAndGetAccessToken("other@test.com");

        mockMvc.perform(patch("/api/draft-post")
                        .header("Authorization", "Bearer " + otherAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "title": "hacked title",
                              "postBody": "hacked body",
                              "postImageUrl": "",
                              "version": 1
                            }
                        """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("content_not_found"));

        PostDraft originalDraft = postDraftRepository.findById(draft.getDraftId()).orElseThrow();

        assertThat(originalDraft.getTitle()).isEqualTo("draft title");
        assertThat(originalDraft.getPostBody()).isEqualTo("draft body");
        assertThat(originalDraft.getVersion()).isEqualTo(1);
    }

    private PostDraft saveDraft(User author, String title, String postBody, String postImageUrl) {
        return postDraftRepository.save(
                new PostDraft(author, title, postBody, postImageUrl)
        );
    }

    private String loginAndGetAccessToken(String email) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "email": "%s",
                              "password": "Test1234!"
                            }
                        """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = new ObjectMapper().readTree(response);

        return json.get("data").get("token").get("accessToken").asText();
    }
}
