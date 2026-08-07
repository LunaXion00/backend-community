package com.example.community.post.integration;

import com.example.community.comment.repository.CommentRepository;
import com.example.community.post.draft.repository.PostDraftRepository;
import com.example.community.post.entity.Post;
import com.example.community.post.repository.PostLikeRepository;
import com.example.community.post.repository.PostRepository;
import com.example.community.post.repository.PostRevisionRepository;
import com.example.community.post.repository.ReportRepository;
import com.example.community.user.entity.User;
import com.example.community.user.entity.UserCredential;
import com.example.community.user.entity.UserRole;
import com.example.community.user.entity.UserStatus;
import com.example.community.user.repository.UserCredentialRepository;
import com.example.community.user.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PostIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;
    @Autowired
    UserCredentialRepository userCredentialRepository;
    @Autowired
    PostRepository postRepository;
    @Autowired
    PostLikeRepository postLikeRepository;
    @Autowired
    ReportRepository reportRepository;
    @Autowired
    PostRevisionRepository postRevisionRepository;
    @Autowired
    CommentRepository commentRepository;
    @Autowired
    PostDraftRepository postDraftRepository;
    @Autowired
    PasswordEncoder passwordEncoder;

    User user;
    User otherUser;
    User admin;

    @BeforeEach
    void setUp() {
        clearDatabase();

        user = userRepository.save(new User("user", "", UserRole.ROLE_USER, UserStatus.ACTIVE));
        otherUser = userRepository.save(new User("other", "", UserRole.ROLE_USER, UserStatus.ACTIVE));
        admin = userRepository.save(new User("admin", "", UserRole.ROLE_ADMIN, UserStatus.ACTIVE));

        userCredentialRepository.save(new UserCredential(user, "user@test.com", passwordEncoder.encode("Test1234!")));
        userCredentialRepository.save(new UserCredential(otherUser, "other@test.com", passwordEncoder.encode("Test1234!")));
        userCredentialRepository.save(new UserCredential(admin, "admin@test.com", passwordEncoder.encode("Test1234!")));
    }

    @AfterEach
    void tearDown() {
        clearDatabase();
    }

    private void clearDatabase() {
        commentRepository.deleteAll();
        postLikeRepository.deleteAll();
        reportRepository.deleteAll();
        postRevisionRepository.deleteAll();
        postDraftRepository.deleteAll();

        postRepository.deleteAll();

        userCredentialRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("JWT 인증 후 게시글을 작성할 수 있다")
    void uploadPost_success() throws Exception {
        String accessToken = loginAndGetAccessToken("user@test.com");

        mockMvc.perform(post("/api/posts")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "title": "test title",
                              "postBody": "test body",
                              "postImageUrl": ""
                            }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("post_create_success"))
                .andExpect(jsonPath("$.data.author.nickname").value("user"))
                .andExpect(jsonPath("$.data.post.title").value("test title"))
                .andExpect(jsonPath("$.data.post.postBody").value("test body"));

        List<Post> posts = postRepository.findAll();

        assertThat(posts).hasSize(1);
        assertThat(posts.get(0).getAuthor().getUserId()).isEqualTo(user.getUserId());
        assertThat(posts.get(0).getTitle()).isEqualTo("test title");
        assertThat(posts.get(0).getDetail().getPostBody()).isEqualTo("test body");
    }

    @Test
    @DisplayName("게시글 목록을 20개씩 안정적으로 페이지 조회한다")
    void getPostList_returnsStablePagesAndMetadata() throws Exception {
        LocalDateTime sameCreatedAt = LocalDateTime.of(2026, 7, 24, 12, 0);
        List<Post> activePosts = new ArrayList<>();
        for (int index = 0; index < 45; index++) {
            Post post = new Post(user, "title " + index, "body", "");
            ReflectionTestUtils.setField(post, "createdAt", sameCreatedAt);
            if (index == 44) {
                post.blindPost();
            }
            activePosts.add(postRepository.save(post));
        }

        Post deletedPost = new Post(user, "deleted title", "body", "");
        ReflectionTestUtils.setField(deletedPost, "createdAt", sameCreatedAt);
        deletedPost.deletePost();
        postRepository.save(deletedPost);

        String accessToken = loginAndGetAccessToken("user@test.com");

        mockMvc.perform(get("/api/posts")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("posts_loading_success"))
                .andExpect(jsonPath("$.data.posts.length()").value(20))
                .andExpect(jsonPath("$.data.posts[0].author.nickname").value("user"))
                .andExpect(jsonPath("$.data.posts[0].post.postId").value(activePosts.get(44).getPostId()))
                .andExpect(jsonPath("$.data.posts[0].post.title").value("숨김 처리된 게시글"))
                .andExpect(jsonPath("$.data.posts[19].post.postId").value(activePosts.get(25).getPostId()))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(45))
                .andExpect(jsonPath("$.data.totalPages").value(3));

        mockMvc.perform(get("/api/posts")
                        .param("page", "1")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.posts.length()").value(20))
                .andExpect(jsonPath("$.data.posts[0].post.postId").value(activePosts.get(24).getPostId()))
                .andExpect(jsonPath("$.data.posts[19].post.postId").value(activePosts.get(5).getPostId()))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(45))
                .andExpect(jsonPath("$.data.totalPages").value(3));

        mockMvc.perform(get("/api/posts")
                        .param("page", "2")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.posts.length()").value(5))
                .andExpect(jsonPath("$.data.posts[0].post.postId").value(activePosts.get(4).getPostId()))
                .andExpect(jsonPath("$.data.posts[4].post.postId").value(activePosts.get(0).getPostId()))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(45))
                .andExpect(jsonPath("$.data.totalPages").value(3));

        mockMvc.perform(get("/api/posts")
                        .param("page", "3")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.posts").isEmpty())
                .andExpect(jsonPath("$.data.page").value(3))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(45))
                .andExpect(jsonPath("$.data.totalPages").value(3));

        mockMvc.perform(get("/api/posts")
                        .param("page", "-1")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_input"));
    }

    @Test
    @DisplayName("JWT 인증 후 게시글 상세를 조회하면 조회수가 증가한다")
    void getPostDetail_success() throws Exception {
        Post post = savePost(user, "test title", "test body", "");
        String accessToken = loginAndGetAccessToken("user@test.com");

        mockMvc.perform(get("/api/posts/" + post.getPostId())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("post_loading_success"))
                .andExpect(jsonPath("$.data.author.userId").value(user.getUserId()))
                .andExpect(jsonPath("$.data.author.nickname").value("user"))
                .andExpect(jsonPath("$.data.post.title").value("test title"))
                .andExpect(jsonPath("$.data.post.postBody").value("test body"))
                .andExpect(jsonPath("$.data.meta.views").value(1));

        Post savedPost = postRepository.findByPostId(post.getPostId()).orElseThrow();
        assertThat(savedPost.getViews()).isEqualTo(1);
    }

    @Test
    @DisplayName("JWT 인증 후 게시글을 수정할 수 있다")
    void modifyPost_success() throws Exception {
        Post post = savePost(user, "test title", "test body", "");
        String accessToken = loginAndGetAccessToken("user@test.com");

        mockMvc.perform(patch("/api/posts/" + post.getPostId())
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "title": "updated title",
                              "postBody": "updated body",
                              "postImageUrl": "updated.png"
                            }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("post_modify_success"))
                .andExpect(jsonPath("$.data.post.title").value("updated title"))
                .andExpect(jsonPath("$.data.post.postBody").value("updated body"))
                .andExpect(jsonPath("$.data.post.revision").value(2));

        Post updatedPost = postRepository.findByPostId(post.getPostId()).orElseThrow();

        assertThat(updatedPost.getTitle()).isEqualTo("updated title");
        assertThat(updatedPost.getDetail().getPostBody()).isEqualTo("updated body");
        assertThat(updatedPost.getDetail().getPostImageUrl()).isEqualTo("updated.png");
        assertThat(updatedPost.getRevision()).isEqualTo(2);
        assertThat(postRevisionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 사용자는 게시글을 수정할 수 없다")
    void modifyPost_otherUser_returns403() throws Exception {
        Post post = savePost(user, "test title", "test body", "");
        String otherAccessToken = loginAndGetAccessToken("other@test.com");

        mockMvc.perform(patch("/api/posts/" + post.getPostId())
                        .header("Authorization", "Bearer " + otherAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "title": "hacked title",
                              "postBody": "hacked body",
                              "postImageUrl": ""
                            }
                        """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("access_denied"));

        Post originalPost = postRepository.findByPostId(post.getPostId()).orElseThrow();

        assertThat(originalPost.getTitle()).isEqualTo("test title");
        assertThat(originalPost.getDetail().getPostBody()).isEqualTo("test body");
        assertThat(postRevisionRepository.count()).isZero();
    }

    @Test
    @DisplayName("JWT 인증 후 게시글을 삭제할 수 있다")
    void deletePost_success() throws Exception {
        Post post = savePost(user, "test title", "test body", "");
        String accessToken = loginAndGetAccessToken("user@test.com");

        mockMvc.perform(delete("/api/posts/" + post.getPostId())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("post_delete_success"));

        Post deletedPost = postRepository.findById(post.getPostId()).orElseThrow();
        assertThat(deletedPost.isDeleted()).isTrue();
    }

    @Test
    @DisplayName("JWT 인증 후 게시글 좋아요와 좋아요 취소가 가능하다")
    void likeAndUnlikePost_success() throws Exception {
        Post post = savePost(user, "test title", "test body", "");
        String accessToken = loginAndGetAccessToken("other@test.com");

        mockMvc.perform(post("/api/posts/" + post.getPostId() + "/likes")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("post_like_success"))
                .andExpect(jsonPath("$.data.likes").value(1))
                .andExpect(jsonPath("$.data.liked").value(true));

        Post likedPost = postRepository.findById(post.getPostId()).orElseThrow();

        assertThat(likedPost.getLikes()).isEqualTo(1);
        assertThat(postLikeRepository.existsByUserAndPost(otherUser.getUserId(), post.getPostId())).isTrue();

        mockMvc.perform(delete("/api/posts/" + post.getPostId() + "/likes")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("post_unlike_success"))
                .andExpect(jsonPath("$.data.likes").value(0))
                .andExpect(jsonPath("$.data.liked").value(false));

        Post unlikedPost = postRepository.findById(post.getPostId()).orElseThrow();

        assertThat(unlikedPost.getLikes()).isZero();
        assertThat(postLikeRepository.existsByUserAndPost(otherUser.getUserId(), post.getPostId())).isFalse();
    }

    @Test
    @DisplayName("게시글 신고 후 블라인드 처리되고 관리자는 조회할 수 있다")
    void reportPost_blindsPostAndAdminCanRead() throws Exception {
        Post post = savePost(user, "test title", "test body", "");
        String otherAccessToken = loginAndGetAccessToken("other@test.com");
        String userAccessToken = loginAndGetAccessToken("user@test.com");
        String adminAccessToken = loginAndGetAccessToken("admin@test.com");

        mockMvc.perform(post("/api/posts/" + post.getPostId() + "/report")
                        .header("Authorization", "Bearer " + otherAccessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "reason": "SPAM",
                              "description": "spam post"
                            }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("post_report_success"))
                .andExpect(jsonPath("$.data.postId").value(post.getPostId()))
                .andExpect(jsonPath("$.data.blinded").value(true));

        Post blindedPost = postRepository.findById(post.getPostId()).orElseThrow();

        assertThat(blindedPost.isBlinded()).isTrue();
        assertThat(reportRepository.countByPostPostId(post.getPostId())).isEqualTo(1);

        mockMvc.perform(get("/api/posts/" + post.getPostId())
                        .header("Authorization", "Bearer " + userAccessToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("access_denied"));

        mockMvc.perform(get("/api/posts/" + post.getPostId())
                        .header("Authorization", "Bearer " + adminAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("post_loading_success"))
                .andExpect(jsonPath("$.data.post.title").value("test title"));
    }

    private Post savePost(User author, String title, String postBody, String postImageUrl) {
        return postRepository.save(new Post(author, title, postBody, postImageUrl));
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
