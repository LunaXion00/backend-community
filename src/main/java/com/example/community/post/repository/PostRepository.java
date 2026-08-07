package com.example.community.post.repository;

import com.example.community.post.entity.Post;
import com.example.community.post.entity.PostStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PostRepository extends JpaRepository<Post,Long> {
    @Query(value = """
        SELECT p.post_id AS postId,
               p.title AS title,
               p.post_status AS postStatus,
               p.created_at AS createdAt,
               p.likes AS likes,
               p.comments AS comments,
               p.views AS views,
               u.user_id AS userId,
               u.user_status AS userStatus,
               u.nickname AS nickname,
               u.profile_image_url AS profileImageUrl
        FROM (
            SELECT p2.post_id, p2.created_at
            FROM posts p2
            WHERE p2.post_status <> :excludedStatus
            ORDER BY p2.created_at DESC, p2.post_id DESC
            LIMIT :offset, 20
        ) page
        JOIN posts p
            ON p.post_id = page.post_id
        LEFT JOIN users u
            ON u.user_id = p.author_id
        ORDER BY page.created_at DESC, page.post_id DESC
        """, nativeQuery = true)
    List<PostListProjection> findByStatusNot(
            @Param("excludedStatus") String excludedStatus,
            @Param("offset") long offset
    );

    long countByStatusNot(PostStatus status);

    interface PostListProjection {
        Long getPostId();
        String getTitle();
        String getPostStatus();
        LocalDateTime getCreatedAt();
        int getLikes();
        int getComments();
        int getViews();
        Long getUserId();
        String getUserStatus();
        String getNickname();
        String getProfileImageUrl();
    }

    @EntityGraph(attributePaths = {"author", "detail"})
    Optional<Post> findByPostId(Long postId);
}
