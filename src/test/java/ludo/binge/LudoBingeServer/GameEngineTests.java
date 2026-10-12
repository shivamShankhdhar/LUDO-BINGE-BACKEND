package ludo.binge.LudoBingeServer;
import ludo.binge.LudoBingeServer.game.domain.GameEngine;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class GameEngineTests {
 final GameEngine engine=new GameEngine();
 @Test void threeSixesRestoreTheWholeTurn(){
  var initial=engine.initial(List.of("a","b"));
  var first=engine.move(engine.roll(initial,6).state(),0).state();
  var second=engine.move(engine.roll(first,6).state(),0).state();
  assertEquals(6,second.players().getFirst().pawns().getFirst());
  var rolled=engine.roll(second,6).state();
  assertEquals(initial.players(),rolled.players());assertEquals(1,rolled.active());assertEquals(0,rolled.sixes());
 }
 @Test void openingAndHomeEntryMatchTheAndroidRules(){
  var state=engine.initial(List.of("a","b"));
  assertEquals(List.of(0,1,2,3),engine.legal(state,1));assertEquals(List.of(),engine.legal(state,2));
  assertEquals(100,engine.next(50,1,false,false));assertEquals(0,engine.next(50,2,false,false));
  assertEquals(51,engine.next(50,1,true,false));assertEquals(100,engine.next(76,1,false,true));
  assertThrows(IllegalArgumentException.class,()->engine.move(state,0));
 }
 @Test void forfeitCannotBeUndoneByThirdSix(){
  var s=engine.initial(List.of("a","b","c"));
  s=engine.move(engine.roll(s,6).state(),0).state();
  s=engine.forfeit(s,1);
  s=engine.move(engine.roll(s,6).state(),0).state();
  s=engine.roll(s,6).state();assertTrue(s.players().get(1).forfeited());assertEquals(2,s.active());
 }
 @Test void repeatedTimeoutsEventuallyFinishWithoutBots(){
  var s=engine.initial(List.of("a","b"));
  for(int i=0;i<10&&!s.phase().equals("FINISHED");i++)s=engine.timeout(s,2).state();
  assertEquals("FINISHED",s.phase());assertNotNull(s.winner());
 }
 @Test void sixPlayerGeometryAndFinishAreConsistent(){
  var s=engine.initial(List.of("a","b","c","d","e","f"));
  assertEquals(83,engine.finish(s));assertEquals(65,engine.global(s,0,0));assertEquals(52,engine.global(s,5,0));
  assertEquals(-1,engine.global(s,0,77));
 }
}
