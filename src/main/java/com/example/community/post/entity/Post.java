package com.example.community.post.entity;

import com.example.community.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Entity
@Table(name="posts",
        indexes = {
                @Index(name = "idx_posts_status_created_at", columnList = "post_status, created_at"),
                @Index(name = "idx_posts_author_created_at", columnList = "author_id, created_at"),
                @Index(name = "idx_posts_list_covering", columnList = "created_at, post_id, post_status")
        }
)
public class Post {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "post_id")
    private Long postId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Column(nullable = false, length = 26)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "post_status", nullable = false)
    private PostStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private boolean modified;

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "blinded_at")
    private LocalDateTime blindedAt;

    @Column(nullable = false)
    private int revision;

    @Column(nullable = false)
    private int likes;

    @Column(nullable = false)
    private int views;

    @Column(nullable = false)
    private int comments;

    @OneToOne(mappedBy = "post", cascade = CascadeType.ALL, orphanRemoval = true)
    private PostDetail detail;

    public Post(User author, String title, String postBody, String postImageUrl) {
        this.author = author;
        this.title = title;
        this.status = PostStatus.ACTIVE;
        this.createdAt = LocalDateTime.now();
        this.modified = false;
        this.revision = 1;
        this.views = 0;
        this.likes = 0;
        this.comments = 0;
        this.detail = new PostDetail(this, postBody, postImageUrl);
    }

    public void modifyPost(String title, String postBody, String postImageUrl) {
        this.title = title;
        this.detail.modify(postBody, postImageUrl);
        this.modified = true;
        this.modifiedAt = LocalDateTime.now();
        this.revision++;
    }
    public void increaseComments(){
        this.comments++;
    }

    public void increaseViews(){
        this.views++;
    }
    public void increaseLikes(){
        this.likes++;
    }
    public void decreaseLikes(){
        this.likes--;
    }
    public void deletePost(){
        this.status = PostStatus.DELETED;
        this.deletedAt = LocalDateTime.now();
    }
    public boolean isDeleted() {
        return PostStatus.DELETED.equals(this.status);
    }
    public void blindPost(){
        this.status = PostStatus.BLINDED;
        this.blindedAt = LocalDateTime.now();
    }
    public boolean isBlinded(){
        return PostStatus.BLINDED.equals(this.status);
    }

}
