package ludo.binge.LudoBingeServer;
import ludo.binge.LudoBingeServer.game.domain.MatchConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MatchConfigurationTests {
 @Test void mapsEveryCount() {
  for (int n=2;n<=6;n++) {
   var config=MatchConfiguration.forPlayers(n);
   assertEquals(n,config.playerCount());
   assertEquals(n<=4 ? MatchConfiguration.Board.CLASSIC : MatchConfiguration.Board.SIX_PLAYER_HEX,config.board());
  }
 }
 @Test void rejectsUnsupportedConfiguration() {
  for (int n : new int[]{0,1,7}) assertThrows(IllegalArgumentException.class,()->MatchConfiguration.forPlayers(n));
  assertThrows(IllegalArgumentException.class,()->new MatchConfiguration(5,MatchConfiguration.Board.CLASSIC,MatchConfiguration.RULESET_VERSION));
  assertThrows(IllegalArgumentException.class,()->new MatchConfiguration(2,MatchConfiguration.Board.CLASSIC,"unknown"));
 }
}
