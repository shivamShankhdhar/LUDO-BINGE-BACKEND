package ludo.binge.LudoBingeServer.game;

import java.util.*;
import java.security.SecureRandom;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import ludo.binge.LudoBingeServer.shared.Store;
import ludo.binge.LudoBingeServer.game.domain.GameEngine;
import static ludo.binge.LudoBingeServer.shared.ApiError.require;
import static ludo.binge.LudoBingeServer.shared.Store.*;

@Service
public class MatchService {
 private final Store db;
 private final boolean workers;
 private final GameEngine engine=new GameEngine();
 private final SecureRandom random=new SecureRandom();
 public MatchService(Store db,@Value("${ludo.workers.enabled:true}") boolean workers){this.db=db;this.workers=workers;}
 public Object snapshot(UUID user,UUID room){
  require(!db.rows("SELECT 1 FROM room_members WHERE room_id=? AND user_id=?",room,user).isEmpty(),403,"NOT_A_MEMBER");
  var matches=db.rows("SELECT * FROM matches WHERE room_id=? ORDER BY created_at DESC LIMIT 1",room);
  if(matches.isEmpty())return Map.of("active",false);
  return view(matches.getFirst());
 }
 private Object view(Map<String,Object> match){
  var state=db.decode(match.get("snapshot"),GameEngine.State.class);
  var result=new LinkedHashMap<String,Object>();result.put("active",true);result.put("matchId",match.get("id"));result.put("revision",match.get("revision"));result.put("state",state);result.put("deadline",match.get("deadline"));result.put("serverTimeMs",System.currentTimeMillis());
  result.put("legalMoves",state.phase().equals("WAITING_MOVE")?engine.legal(state,state.dice()):List.of());
  result.put("players",db.rows("SELECT user_id,seat,profile FROM match_players WHERE match_id=? ORDER BY seat",match.get("id")).stream().map(p->Map.of("userId",p.get("user_id"),"seat",p.get("seat"),"profile",db.decode(p.get("profile"),Object.class))).toList());
  var events=db.rows("SELECT payload FROM game_events WHERE match_id=? ORDER BY seq DESC LIMIT 1",match.get("id"));result.put("lastEvent",events.isEmpty()?Map.of():db.decode(events.getFirst().get("payload"),Object.class));return result;
 }
 public Object command(UUID user,UUID match,UUID key,long revision,String action,Integer pawn){
  return db.mutate(user,key,"match:"+match,Arrays.asList(revision,action,pawn),()->{
   var row=db.one("SELECT * FROM matches WHERE id=? FOR UPDATE",match);
   require(!db.rows("SELECT 1 FROM match_players WHERE match_id=? AND user_id=?",match,user).isEmpty(),403,"NOT_A_PLAYER");
   require(((Number)row.get("revision")).longValue()==revision,409,"STALE_MATCH_REVISION");
   var state=db.decode(row.get("snapshot"),GameEngine.State.class);require(!state.phase().equals("FINISHED"),409,"MATCH_FINISHED");
   int seat=-1;for(int i=0;i<state.count();i++)if(state.players().get(i).userId().equals(user.toString()))seat=i;
   GameEngine.Transition transition;Integer rolled=null;
   if(action.equals("FORFEIT"))transition=new GameEngine.Transition(engine.forfeit(state,seat),List.of(),List.of("PLAYER_FORFEITED"));
   else {
    require(state.active()==seat,409,"NOT_YOUR_TURN");require(!state.players().get(seat).forfeited(),409,"PLAYER_FORFEITED");
    require(((java.sql.Timestamp)row.get("deadline")).toInstant().isAfter(java.time.Instant.now()),409,"TURN_EXPIRED");
    state=engine.human(state,seat);
    if(action.equals("ROLL")){rolled=random.nextInt(6)+1;transition=engine.roll(state,rolled);}
    else {require(action.equals("MOVE")&&pawn!=null,400,"INVALID_COMMAND");transition=engine.move(state,pawn);}
   }
   save(row,transition,rolled);return view(db.one("SELECT * FROM matches WHERE id=?",match));
  });
 }
 private void save(Map<String,Object> row,GameEngine.Transition transition,Integer rolled){
  var state=transition.state();UUID match=uuid(row.get("id")),room=uuid(row.get("room_id"));long revision=((Number)row.get("revision")).longValue()+1;
  var event=new LinkedHashMap<String,Object>();event.put("effects",transition.effects());event.put("path",transition.path());event.put("dice",rolled);event.put("revision",revision);
  db.update("UPDATE matches SET snapshot=?::jsonb,revision=?,status=?,deadline=now()+interval '30 seconds',finished_at=CASE WHEN ? THEN now() ELSE NULL END WHERE id=?",db.encode(state),revision,state.phase().equals("FINISHED")?"FINISHED":"PLAYING",state.phase().equals("FINISHED"),match);
  db.update("INSERT INTO game_events(match_id,seq,event_id,payload) VALUES (?,?,?,?::jsonb)",match,revision,id(),db.encode(event));
  if(state.phase().equals("FINISHED")){
   db.update("UPDATE rooms SET phase='FINISHED',revision=revision+1 WHERE id=?",room);db.update("DELETE FROM user_allocations WHERE resource_id=?",room);
   for(var player:state.players())if(db.update("INSERT INTO result_applications(match_id,user_id) VALUES (?,?) ON CONFLICT DO NOTHING",match,uuid(player.userId()))==1)
    db.update("INSERT INTO player_stats(user_id,played,wins,forfeits) VALUES (?,1,?,?) ON CONFLICT(user_id) DO UPDATE SET played=player_stats.played+1,wins=player_stats.wins+excluded.wins,forfeits=player_stats.forfeits+excluded.forfeits",uuid(player.userId()),player.userId().equals(state.winner())?1:0,player.forfeited()?1:0);
  }
 }
 @Scheduled(fixedDelay=1000)
 public void tick(){
  if(!workers)return;
  try {
   db.tx(()->{
    var rooms=db.rows("SELECT * FROM rooms WHERE phase='COUNTDOWN' AND countdown_at<=now() ORDER BY countdown_at LIMIT 10 FOR UPDATE SKIP LOCKED");
    for(var room:rooms){UUID roomId=uuid(room.get("id"));var players=db.rows("SELECT u.id,u.display_name,u.avatar_url,u.avatar_version,m.seat,(u.connected_until>now()) AS connected FROM room_members m JOIN users u ON u.id=m.user_id WHERE m.room_id=? ORDER BY seat",roomId);
     if(players.size()!=(int)room.get("capacity")||players.stream().anyMatch(p->!Boolean.TRUE.equals(p.get("connected")))){db.update("UPDATE rooms SET phase='LOBBY',countdown_at=NULL,revision=revision+1 WHERE id=?",roomId);db.update("UPDATE room_members SET ready=false WHERE room_id=?",roomId);continue;}
     UUID match=id();var state=engine.initial(players.stream().map(p->p.get("id").toString()).toList());db.update("INSERT INTO matches(id,room_id,snapshot,deadline) VALUES (?,?,?::jsonb,now()+interval '30 seconds')",match,roomId,db.encode(state));
     for(int seat=0;seat<players.size();seat++){var p=players.get(seat);db.update("INSERT INTO match_players(match_id,user_id,seat,profile) VALUES (?,?,?,?::jsonb)",match,p.get("id"),seat,db.encode(p));}
     db.update("UPDATE rooms SET phase='PLAYING',revision=revision+1 WHERE id=?",roomId);
    }return null;
   });
   db.tx(()->{for(var match:db.rows("SELECT * FROM matches WHERE status='PLAYING' AND deadline<=now() ORDER BY deadline LIMIT 10 FOR UPDATE SKIP LOCKED")){var state=db.decode(match.get("snapshot"),GameEngine.State.class);int dice=random.nextInt(6)+1;save(match,engine.timeout(state,dice),dice);}return null;});
  }catch(org.springframework.dao.DataAccessException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Match worker could not access database: {}",e.getClass().getSimpleName());}
 }
}
