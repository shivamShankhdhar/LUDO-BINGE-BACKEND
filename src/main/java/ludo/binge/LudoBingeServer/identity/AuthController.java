package ludo.binge.LudoBingeServer.identity;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1")
public class AuthController {
 private final IdentityService identity;
 public AuthController(IdentityService identity){this.identity=identity;}
 public record Guest(@NotBlank @Size(max=40) String displayName){}
 @PostMapping("/auth/guest") Object guest(@Valid @RequestBody Guest body,HttpServletRequest request){return identity.guest(body.displayName(),request.getRemoteAddr());}
 public record Login(@NotNull UUID challengeId,@NotBlank @Size(max=16384) String idToken){}
 public record Refresh(@NotBlank @Size(max=512) String refreshToken,UUID requestId){}
 @PostMapping("/auth/challenge") Object challenge(HttpServletRequest request){return identity.challenge(request.getRemoteAddr());}
 @PostMapping("/auth/google") Object login(@Valid @RequestBody Login body,HttpServletRequest request){return identity.login(body.challengeId(),body.idToken(),request.getRemoteAddr());}
 @PostMapping("/auth/refresh") Object refresh(@Valid @RequestBody Refresh body,HttpServletRequest request){return identity.refresh(body.refreshToken(),request.getRemoteAddr(),body.requestId());}
 @PostMapping("/auth/logout") Object logout(org.springframework.security.core.Authentication auth){return identity.logout((IdentityService.Actor)auth.getPrincipal());}
 @GetMapping("/me") Object me(org.springframework.security.core.Authentication auth){return identity.profile(((IdentityService.Actor)auth.getPrincipal()).userId());}
}
