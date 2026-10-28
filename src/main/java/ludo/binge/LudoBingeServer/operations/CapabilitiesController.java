package ludo.binge.LudoBingeServer.operations;
import java.util.*;
import java.util.stream.IntStream;
import ludo.binge.LudoBingeServer.game.domain.MatchConfiguration;
import ludo.binge.LudoBingeServer.identity.IdentityService;
import org.springframework.web.bind.annotation.*;
@RestController
public class CapabilitiesController {
 private final IdentityService identity;
 @org.springframework.beans.factory.annotation.Value("${ludo.turn.urls:}") private String turnUrls;
 @org.springframework.beans.factory.annotation.Value("${ludo.turn.secret:}") private String turnSecret;
 public CapabilitiesController(IdentityService identity){this.identity=identity;}
 @GetMapping({"/api/v1/capabilities","/api/v1/config"}) public Object capabilities(){return Map.of("protocolVersion",1,"status","ONLINE","onlinePlayEnabled",true,"guestSignInEnabled",identity.guestConfigured(),"googleSignInEnabled",identity.configured(),"voiceEnabled",!turnUrls.isBlank()&&!turnSecret.isBlank(),"plannedMatchConfigurations",IntStream.rangeClosed(2,6).mapToObj(MatchConfiguration::forPlayers).toList());}
}
