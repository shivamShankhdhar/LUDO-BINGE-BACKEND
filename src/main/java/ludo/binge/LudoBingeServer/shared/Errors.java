package ludo.binge.LudoBingeServer.shared;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
@RestControllerAdvice
public class Errors {
 @ExceptionHandler(org.springframework.dao.DataAccessException.class) ResponseEntity<?> unavailable(Exception e) {
  org.slf4j.LoggerFactory.getLogger(Errors.class).error("Database request failed", e);
  String detail = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
  return ResponseEntity.status(503).body(Map.of("code","SERVICE_UNAVAILABLE","retryable",true,"detail",detail != null ? detail : "unknown"));
 }
 @ExceptionHandler(ApiError.class) ResponseEntity<?> domain(ApiError e) { return ResponseEntity.status(e.status).body(Map.of("code",e.code,"retryable",e.status==503||e.status==429)); }
 @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<?> conflict(Exception e) { return ResponseEntity.status(409).body(Map.of("code","CONFLICT","retryable",false)); }
 @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,IllegalArgumentException.class}) ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST","retryable",false)); }
 @ExceptionHandler(Throwable.class) ResponseEntity<?> fallback(Throwable e) {
  org.slf4j.LoggerFactory.getLogger(Errors.class).error("Unhandled error", e);
  return ResponseEntity.status(500).body(Map.of("code","SERVER_ERROR","retryable",true));
 }
}
