package com.example.community.comment.integration;

import com.example.community.comment.entity.Comment;
import com.example.community.comment.repository.CommentRepository;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class CommentIntegrationTest {
    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    CommentRepository commentRepository;
    @Autowired
    PostRepository postRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    UserCredentialRepository userCredentialRepository;
    @Autowired
    PasswordEncoder passwordEncoder;
    ObjectMapper objectMapper = new ObjectMapper();

    User postAuthor;
    User commenter;
    User otherUser;
    Post post;

    @BeforeEach
    void setUp() {
        userCredentialRepository.deleteAll();

        jdbcTemplate.update("UPDATE comments SET parent_comment_id = NULL");
        commentRepository.deleteAllInBatch();

        postRepository.deleteAll();
        userRepository.deleteAll();

        postAuthor = userRepository.save(
                new User("author", "", UserRole.ROLE_USER, UserStatus.ACTIVE)
        );
        commenter = userRepository.save(
                new User("commenter", "", UserRole.ROLE_USER, UserStatus.ACTIVE)
        );
        otherUser = userRepository.save(
                new User("other", "", UserRole.ROLE_USER, UserStatus.ACTIVE)
        );

        userCredentialRepository.save(
                new UserCredential(
                        commenter,
                        "commenter@test.com",
                        passwordEncoder.encode("Test1234!")
                )
        );

        userCredentialRepository.save(
                new UserCredential(
                        otherUser,
                        "other@test.com",
                        passwordEncoder.encode("Test1234!")
                )
        );

        post = postRepository.save(
                new Post(postAuthor, "testpost", "testpostbody", "")
        );
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("UPDATE comments SET parent_comment_id = NULL");
        commentRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("댓글 작성 - 저장 확인")
    void uploadComment_success() throws Exception {
        String accessToken = loginAndGetAccessToken();
        mockMvc.perform(post("/api/posts/"+post.getPostId()+"/comments")
                .header("Authorization", "Bearer "+accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "commentBody":"test comment"
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("comment_create_success"))
                .andExpect(jsonPath("$.data.comment.parentCommentId").value(nullValue()))
                .andExpect(jsonPath("$.data.comment.commentBody").value("test comment"));
        List<Comment> comments = commentRepository.findListByPost(post.getPostId());

        assertThat(comments).hasSize(1);

        Comment savedComment = comments.get(0);

        assertThat(savedComment.getCommentBody()).isEqualTo("test comment");
        assertThat(savedComment.getAuthor().getUserId()).isEqualTo(commenter.getUserId());
        assertThat(savedComment.getParentComment()).isNull();

        Post savedPost = postRepository.findById(post.getPostId()).orElseThrow();
        assertThat(savedPost.getComments()).isEqualTo(1);
    }

    @Test
    @DisplayName("댓글 수정 요청 - 변경 확인")
    void modifyComment_success() throws Exception{
        Comment comment = saveCommentByCommenter();
        String accessToken = loginAndGetAccessToken();
        mockMvc.perform(patch("/api/posts/"+post.getPostId()+"/comments/"+comment.getCommentId())
                        .header("Authorization", "Bearer "+accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                           {
                            "commentBody": "modified comment",
                            "parentCommentId": 999999
                           }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("comment_modify_success"))
                .andExpect(jsonPath("$.data.comment.parentCommentId").value(nullValue()))
                .andExpect(jsonPath("$.data.comment.commentBody").value("modified comment"));
        List<Comment> comments = commentRepository.findListByPost(post.getPostId());

        assertThat(comments).hasSize(1);

        Comment savedComment = commentRepository.findById(comment.getCommentId()).orElseThrow();

        assertThat(savedComment.getCommentBody()).isEqualTo("modified comment");
        assertThat(savedComment.getParentComment()).isNull();
        assertThat(savedComment.isModified()).isTrue();
        assertThat(savedComment.getModifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("댓글 조회 요청 확인")
    void getComments_success() throws Exception{
        saveCommentByCommenter();
        String accessToken = loginAndGetAccessToken();
        mockMvc.perform(get("/api/posts/"+post.getPostId()+"/comments")
                        .header("Authorization", "Bearer "+accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("comments_get_success"))
                .andExpect(jsonPath("$.data[0].author.userId").value(commenter.getUserId()))
                .andExpect(jsonPath("$.data[0].author.nickname").value("commenter"))
                .andExpect(jsonPath("$.data[0].comment.parentCommentId").value(nullValue()))
                .andExpect(jsonPath("$.data[0].comment.commentBody").value("test comment"));
        List<Comment> comments = commentRepository.findListByPost(post.getPostId());

        assertThat(comments).hasSize(1);

        Comment savedComment = comments.get(0);

        assertThat(savedComment.getCommentBody()).isEqualTo("test comment");
        assertThat(savedComment.getAuthor().getUserId()).isEqualTo(commenter.getUserId());
    }

    @Test
    @DisplayName("최상위 댓글부터 다단계 대댓글까지 생성하고 평면 목록에서 직접 부모 ID를 조회한다.")
    void createAndGetNestedReplies_success() throws Exception {
        String accessToken = loginAndGetAccessToken();

        JsonNode topLevel = createComment(accessToken, post, "top level", null);
        long topLevelId = topLevel.get("commentId").asLong();
        assertThat(topLevel.get("parentCommentId").isNull()).isTrue();

        JsonNode reply = createComment(accessToken, post, "reply", topLevelId);
        long replyId = reply.get("commentId").asLong();
        assertThat(reply.get("parentCommentId").asLong()).isEqualTo(topLevelId);

        JsonNode nestedReply = createComment(accessToken, post, "nested reply", replyId);
        long nestedReplyId = nestedReply.get("commentId").asLong();
        assertThat(nestedReply.get("parentCommentId").asLong()).isEqualTo(replyId);

        String listResponse = mockMvc.perform(get("/api/posts/" + post.getPostId() + "/comments")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("comments_get_success"))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode comments = objectMapper.readTree(listResponse).get("data");
        JsonNode topLevelInList = findCommentById(comments, topLevelId);
        JsonNode replyInList = findCommentById(comments, replyId);
        JsonNode nestedReplyInList = findCommentById(comments, nestedReplyId);
        assertThat(topLevelInList.get("parentCommentId").isNull()).isTrue();
        assertThat(replyInList.get("parentCommentId").asLong()).isEqualTo(topLevelId);
        assertThat(nestedReplyInList.get("parentCommentId").asLong()).isEqualTo(replyId);

        Comment savedTopLevel = commentRepository.findCommentWithPost(post.getPostId(), topLevelId).orElseThrow();
        Comment savedReply = commentRepository.findCommentWithPost(post.getPostId(), replyId).orElseThrow();
        Comment savedNestedReply = commentRepository.findCommentWithPost(post.getPostId(), nestedReplyId).orElseThrow();
        assertThat(savedTopLevel.getParentComment()).isNull();
        assertThat(savedReply.getParentComment().getCommentId()).isEqualTo(topLevelId);
        assertThat(savedNestedReply.getParentComment().getCommentId()).isEqualTo(replyId);
        assertThat(postRepository.findById(post.getPostId()).orElseThrow().getComments()).isEqualTo(3);
    }

    @Test
    @DisplayName("다른 게시글의 댓글을 부모로 지정하면 404이고 댓글과 댓글 수가 증가하지 않는다.")
    void createReply_parentFromAnotherPost_returns404WithoutChanges() throws Exception {
        Post otherPost = postRepository.save(new Post(postAuthor, "other post", "other body", ""));
        Comment otherPostComment = commentRepository.save(new Comment(commenter, otherPost, null, "other comment"));
        String accessToken = loginAndGetAccessToken();
        long commentsBefore = commentRepository.count();
        int postCommentCountBefore = postRepository.findById(post.getPostId()).orElseThrow().getComments();

        mockMvc.perform(post("/api/posts/" + post.getPostId() + "/comments")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(commentRequest("invalid reply", otherPostComment.getCommentId()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("content_not_found"));

        assertThat(commentRepository.count()).isEqualTo(commentsBefore);
        assertThat(postRepository.findById(post.getPostId()).orElseThrow().getComments()).isEqualTo(postCommentCountBefore);
        assertThat(commentRepository.findListByPost(post.getPostId())).isEmpty();
    }

    @Test
    @DisplayName("삭제된 댓글을 부모로 지정하면 400이고 댓글과 댓글 수가 증가하지 않는다.")
    void createReply_deletedParent_returns400WithoutChanges() throws Exception {
        String accessToken = loginAndGetAccessToken();
        JsonNode parent = createComment(accessToken, post, "parent", null);
        long parentId = parent.get("commentId").asLong();

        mockMvc.perform(delete("/api/posts/" + post.getPostId() + "/comments/" + parentId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        long commentsBefore = commentRepository.count();
        int postCommentCountBefore = postRepository.findById(post.getPostId()).orElseThrow().getComments();

        mockMvc.perform(post("/api/posts/" + post.getPostId() + "/comments")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(commentRequest("invalid reply", parentId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_input"));

        assertThat(commentRepository.count()).isEqualTo(commentsBefore);
        assertThat(postRepository.findById(post.getPostId()).orElseThrow().getComments()).isEqualTo(postCommentCountBefore);
        assertThat(commentRepository.findListByPost(post.getPostId())).hasSize(1);
    }

    @Test
    @DisplayName("댓글 삭제 요청 확인")
    void deleteComments_success() throws Exception{
        Comment comment = saveCommentByCommenter();
        String accessToken = loginAndGetAccessToken();
        mockMvc.perform(delete("/api/posts/"+post.getPostId()+"/comments/"+comment.getCommentId())
                        .header("Authorization", "Bearer "+accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("comment_delete_success"))
                .andExpect(jsonPath("$.data.commentId").value(comment.getCommentId()))
                .andExpect(jsonPath("$.data.deleted").value(true));
        comment = commentRepository.findById(comment.getCommentId()).orElseThrow();
        assertThat(comment.getCommentBody()).isEqualTo("삭제된 댓글입니다");
    }

    private String loginAndGetAccessToken() throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "email": "commenter@test.com",
                          "password": "Test1234!"
                        }
                    """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);

        return json.get("data").get("token").get("accessToken").asText();
    }
    private Comment saveCommentByCommenter() {
        Comment savedComment = commentRepository.save(
                new Comment(commenter, post, null, "test comment")
        );

        Post savedPost = postRepository.findById(post.getPostId()).orElseThrow();
        savedPost.increaseComments();
        postRepository.save(savedPost);

        return savedComment;
    }

    private JsonNode createComment(String accessToken, Post targetPost, String commentBody, Long parentCommentId) throws Exception {
        String response = mockMvc.perform(post("/api/posts/" + targetPost.getPostId() + "/comments")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(commentRequest(commentBody, parentCommentId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("comment_create_success"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response).get("data").get("comment");
    }

    private Map<String, Object> commentRequest(String commentBody, Long parentCommentId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("commentBody", commentBody);
        request.put("parentCommentId", parentCommentId);
        return request;
    }

    private JsonNode findCommentById(JsonNode comments, long commentId) {
        for (JsonNode commentResponse : comments) {
            JsonNode comment = commentResponse.get("comment");
            if (comment.get("commentId").asLong() == commentId) {
                return comment;
            }
        }
        throw new AssertionError("comment not found: " + commentId);
    }
}
