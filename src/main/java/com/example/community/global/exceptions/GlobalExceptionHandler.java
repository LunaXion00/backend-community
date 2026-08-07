package com.example.community.global.exceptions;

import com.example.community.global.ApiResponse;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    // 400, 올바르지 않은 입력값
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleInvalidInputException(MethodArgumentNotValidException e) {
        Map<String, String> errors = new HashMap<>();

        e.getBindingResult().getFieldErrors().forEach(error -> {
            errors.put(error.getField(), error.getDefaultMessage());
        });

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiResponse<>("invalid_input", errors));
    }

    // 400, 기타 올바르지 않은 입력 형식.
    @ExceptionHandler(InvalidInputException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidInputException(InvalidInputException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse<>("invalid_input", null));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableMessage(HttpMessageNotReadableException e){
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse<>("invalid_input", null));
    }

    // 401, 등록되지 않은 이메일 -> 유저 확인되지 않음
    @ExceptionHandler(NotRegisteredException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleNotRegisteredFoundException(NotRegisteredException e) {
        Map<String, String> errors = new HashMap<>();
        errors.put("email", "user_not_found");

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiResponse<>("user_not_found", errors));
    }

    // 401, 올바르지 않은 비밀번호
    @ExceptionHandler(PasswordInvalidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handlePasswordInvalidException(PasswordInvalidException e) {
        Map<String, String> errors = new HashMap<>();
        errors.put("password", "password_invalid");

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiResponse<>("password_invalid", errors));
    }

    // 401, 비로그인이나 토큰이 요청에 없는 경우
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleUnauthorizedException(UnauthorizedException e) {
        Map<String, String> errors = new HashMap<>();
        errors.put("token", "unauthorized_user");

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiResponse<>("unauthorized_user", errors));
    }

    // 403, 권한이 없고, 관리자가 아닌 경우
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiResponse<Void>> handleForbiddenException(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ApiResponse<>("access_denied", null));
    }

    // 404, 올바르지 않은 페이지(파라미터 등)
    @ExceptionHandler(ContentNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleContentNotFoundException(ContentNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiResponse<>("content_not_found", null));
    }

    //409, 중복된 값
    @ExceptionHandler(AlreadyExistsException.class)
    public ResponseEntity<ApiResponse<Void>> handleAlreadyExistsException(AlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiResponse<>("already_exists", null));
    }

    // 409, 이미 신고한 게시글
    @ExceptionHandler(AlreadyReportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAlreadyReportedException(AlreadyReportedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiResponse<>("already_reported", null));
    }

    // 409, 좋아요 등 상태가 중복된 경우.
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflictException(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiResponse<>("state_conflict", null));
    }

    // 503, Redis session operation unavailable
    @ExceptionHandler({RedisConnectionFailureException.class, RedisSystemException.class})
    public ResponseEntity<ApiResponse<Void>> handleRedisUnavailableException(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiResponse<>("session_unavailable", null));
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleDisconnectedClient(AsyncRequestNotUsableException exception) {

    }

    // 500, 내부 서버 오류
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> internalServerError(Exception e) {
        e.printStackTrace();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse<>("internal_server_error", null));
    }
}
