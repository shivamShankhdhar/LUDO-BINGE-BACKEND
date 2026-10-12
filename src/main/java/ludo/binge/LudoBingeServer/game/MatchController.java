package ludo.binge.LudoBingeServer.game;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import ludo.binge.LudoBingeServer.identity.IdentityService.Actor;
@RestController
@RequestMapping("/api/v1")
public class MatchController {
 private final MatchService matches;
 public MatchController(MatchService matches){this.matches=matches;}
 public record Command(@Min(0) long revision,@NotBlank @Pattern(regexp="ROLL|MOVE|FORFEIT") String action,@Min(0) @Max(3) Integer pawn){}
 @GetMapping("/rooms/{room}/match") Object snapshot(Authentication auth,@PathVariable UUID room){return matches.snapshot(((Actor)auth.getPrincipal()).userId(),room);}
 @PostMapping("/matches/{match}/commands") Object command(Authentication auth,@PathVariable UUID match,@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Command body){return matches.command(((Actor)auth.getPrincipal()).userId(),match,key,body.revision(),body.action(),body.pawn());}
}
