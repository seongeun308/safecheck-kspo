package io.github.seongeun308.safecheck.controller;

import io.github.seongeun308.safecheck.support.ImagePreprocessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * 사용자에게 보여줄 오류 응답을 만든다.
 *
 * <p>내부 예외 메시지를 그대로 노출하지 않는다. 판정 실패 원인은 로그로 남기고,
 * 클라이언트에는 무엇을 고치면 되는지만 알린다.
 */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    /** 업로드한 파일이 이미지가 아니거나 손상된 경우. */
    @ExceptionHandler(ImagePreprocessor.InvalidImageException.class)
    public ResponseEntity<ErrorResponse> handleInvalidImage(
            ImagePreprocessor.InvalidImageException e) {
        log.info("이미지 검증 실패: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_IMAGE", e.getMessage()));
    }

    /** 위치구분처럼 허용 목록이 정해진 값이 잘못 들어온 경우. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        log.info("잘못된 요청: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(
            MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_REQUEST",
                        "필수 항목이 빠졌습니다: " + e.getParameterName()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("IMAGE_TOO_LARGE",
                        "이미지가 너무 큽니다. 조금 작은 사진으로 다시 시도해주세요."));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPart(MissingServletRequestPartException e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_REQUEST",
                        "필수 항목이 빠졌습니다: " + e.getRequestPartName()));
    }

    /** 판정 모델 호출 실패, 응답 파싱 실패 등. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("판정 처리 중 오류", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("JUDGEMENT_FAILED",
                        "판정에 실패했습니다. 잠시 후 다시 시도해주세요."));
    }

    public record ErrorResponse(String code, String message) {}
}
