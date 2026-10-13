package ludo.binge.LudoBingeServer;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import ludo.binge.LudoBingeServer.shared.*;
import ludo.binge.LudoBingeServer.room.RoomService;
import ludo.binge.LudoBingeServer.online.MatchmakingService;
import ludo.binge.LudoBingeServer.identity.IdentityService;
import ludo.binge.LudoBingeServer.identity.RefreshRetryCipher;
import ludo.binge.LudoBingeServer.game.MatchService;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static ludo.binge.LudoBingeServer.shared.Store.*;

/** A disposable local PostgreSQL instance, never the configured application database. */
class OnlineExperienceTests {
 static EmbeddedPostgres postgres;Store db;RoomService rooms;MatchmakingService queue;
 @BeforeAll static void start() throws Exception {
  postgres=EmbeddedPostgres.builder().setPort(0).start();
  Flyway.configure().dataSource(postgres.getPostgresDatabase()).defaultSchema("public").locations("classpath:db/migration").load().migrate();
 }
 @AfterAll static void stop() throws Exception {if(postgres!=null)postgres.close();}
 @BeforeEach void prepare(){var source=postgres.getPostgresDatabase();db=new Store(new JdbcTemplate(source),JsonMapper.builder().findAndAddModules().build(),new JdbcTransactionManager(source));rooms=new RoomService(db,"https://example.test");queue=new MatchmakingService(db,rooms,true);db.update("TRUNCATE users, proposals CASCADE");}
 UUID user(){UUID u=id();db.update("INSERT INTO users(id,google_sub,display_name,connected_until) VALUES (?,?,?,now()+interval '5 minutes')",u,u.toString(),"Player");return u;}
 Map<?,?> map(Object x){return (Map<?,?>)x;}
 @Test void createRetryHasOneRoomAndInvite(){UUID u=user(),key=id();Object first=db.mutate(u,key,"create",2,()->rooms.create(u,2));Object second=db.mutate(u,key,"create",2,()->rooms.create(u,2));
  assertEquals(map(map(first).get("room")).get("id").toString(),map(map(second).get("room")).get("id").toString());assertEquals(1,db.jdbc.queryForObject("SELECT count(*) FROM rooms",Integer.class));
  String code=map(map(first).get("invite")).get("code").toString();assertEquals(8,code.length());assertNotNull(rooms.resolve(code));
 }
 @Test void sixPlayersReserveAcceptAndStartExactlyOneHexMatch() throws Exception {
  List<UUID> players=new ArrayList<>();for(int i=0;i<6;i++){UUID u=user();players.add(u);queue.enter(u,6);}queue.tick();
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){List<Future<?>> tasks=new ArrayList<>();for(UUID u:players)tasks.add(pool.submit(()->queue.accept(u)));for(var task:tasks)task.get();}
  assertEquals(1,db.jdbc.queryForObject("SELECT count(*) FROM rooms",Integer.class));assertEquals(6,db.jdbc.queryForObject("SELECT count(*) FROM room_members",Integer.class));
  assertEquals("SIX_PLAYER_HEX",db.one("SELECT board FROM rooms").get("board"));
  db.update("UPDATE rooms SET countdown_at=now()-interval '1 second'");new MatchService(db,true).tick();
  assertEquals(1,db.jdbc.queryForObject("SELECT count(*) FROM matches",Integer.class));assertEquals("PLAYING",db.one("SELECT phase FROM rooms").get("phase"));
 }
 @Test void staleAcceptanceCannotConfirmADifferentProposal(){
  UUID a=user(),b=user();queue.enter(a,2);queue.enter(b,2);queue.tick();
  assertThrows(ApiError.class,()->queue.accept(a,id()));
  assertEquals("RESERVED",queue.ticket(a).get("status"));
  UUID proposal=uuid(queue.ticket(a).get("proposal_id"));queue.accept(a,proposal);queue.accept(b,proposal);
  assertEquals("MATCHED",map(queue.accept(a,proposal)).get("status"));
 }
 @Test void acceptanceRetryAfterMatchCreationReturnsSameRoom(){
  UUID a=user(),b=user();queue.enter(a,2);queue.enter(b,2);queue.tick();queue.accept(a);queue.accept(b);
  var before=queue.ticket(a);var retried=map(queue.accept(a));
  assertEquals("MATCHED",retried.get("status"));assertEquals(before.get("room_id"),retried.get("room_id"));
  assertEquals(1,db.jdbc.queryForObject("SELECT count(*) FROM rooms",Integer.class));
 }
 @Test void hostReceivesGuestRequestAndAcceptanceSeatsCorrectProfile(){
  UUID host=user(),guest=user();db.update("UPDATE users SET display_name='Guest name',avatar_url='https://example.test/guest.png' WHERE id=?",guest);
  var created=map(db.tx(()->rooms.create(host,2)));UUID room=uuid(map(created.get("room")).get("id"));
  String code=map(created.get("invite")).get("code").toString();
  db.tx(()->rooms.join(guest,room,code));db.tx(()->rooms.join(guest,room,code));
  var snapshot=map(rooms.snapshot(host,room));var requests=(List<?>)snapshot.get("joinRequests");
  assertEquals(1,requests.size());var request=map(requests.getFirst());
  assertEquals(guest,request.get("user_id"));assertEquals("Guest name",request.get("display_name"));
  db.tx(()->rooms.decide(host,room,uuid(request.get("id")),true,((Number)snapshot.get("revision")).longValue()));
  var guestView=map(rooms.snapshot(guest,room));assertFalse(guestView.containsKey("joinRequests"));
  var members=(List<?>)guestView.get("members");assertEquals(2,members.size());
  assertEquals(guest,map(members.get(1)).get("userId"));assertEquals("Guest name",map(members.get(1)).get("displayName"));
  assertTrue(((List<?>)map(rooms.snapshot(host,room)).get("joinRequests")).isEmpty());
 }
 @Test void proposalContainsBothDistinctPlayerProfilesAndEnoughConfirmationTime(){
  UUID a=user(),b=user();db.update("UPDATE users SET display_name='Opponent' WHERE id=?",b);
  queue.enter(a,2);queue.enter(b,2);queue.tick();var ticket=queue.ticket(a);
  var players=(List<?>)ticket.get("players");assertEquals(2,players.size());
  assertTrue(players.stream().map(this::map).anyMatch(p->b.equals(p.get("userId"))&&"Opponent".equals(p.get("displayName"))));
  assertTrue(db.jdbc.queryForObject("SELECT expires_at>now()+interval '20 seconds' FROM proposals",Boolean.class));
 }
 @Test void acceptedPlayerReturnsToQueueWhenOpponentDeclines(){UUID a=user(),b=user();queue.enter(a,2);queue.enter(b,2);queue.tick();queue.accept(a);queue.cancel(b);
  assertEquals("SEARCHING",queue.ticket(a).get("status"));assertEquals("CANCELLED",queue.ticket(b).get("status"));assertEquals(1,db.jdbc.queryForObject("SELECT count(*) FROM user_allocations",Integer.class));
 }
 @Test void expiredReservationReleasesUnresponsivePlayers(){UUID a=user(),b=user();queue.enter(a,2);queue.enter(b,2);queue.tick();db.update("UPDATE proposals SET expires_at=now()-interval '1 second'");queue.tick();assertEquals("EXPIRED",queue.ticket(a).get("status"));assertEquals(0,db.jdbc.queryForObject("SELECT count(*) FROM user_allocations",Integer.class));queue.cancel(a);assertEquals("CANCELLED",queue.ticket(a).get("status"));}
 @Test void blockedPlayersNeverMatch(){UUID a=user(),b=user();db.update("INSERT INTO blocks VALUES (?,?)",a,b);queue.enter(a,2);queue.enter(b,2);queue.tick();assertEquals("SEARCHING",queue.ticket(a).get("status"));assertEquals(0,db.jdbc.queryForObject("SELECT count(*) FROM proposals",Integer.class));}
 @Test void queueAndPrivateRoomCannotBothAllocateUser(){UUID u=user();queue.enter(u,2);assertThrows(ApiError.class,()->db.tx(()->rooms.create(u,2)));assertEquals(0,db.jdbc.queryForObject("SELECT count(*) FROM rooms",Integer.class));}
 @Test void nonmemberCannotReadRoomAndHostTransfers(){UUID host=user(),guest=user(),stranger=user();var created=map(db.tx(()->rooms.create(host,2)));UUID room=uuid(map(created.get("room")).get("id"));db.tx(()->{rooms.seat(room,guest,1);return null;});assertThrows(ApiError.class,()->rooms.snapshot(stranger,room));long rev=((Number)map(rooms.snapshot(host,room)).get("revision")).longValue();db.tx(()->rooms.leave(host,room,host,rev));assertEquals(guest,db.one("SELECT host_id FROM rooms WHERE id=?",room).get("host_id"));}
 @Test void refreshRetryReturnsSameCredentialsAndReplayRevokes(){UUID u=user(),sid=id(),family=id(),request=id();String token=token();db.update("INSERT INTO refresh_sessions(id,user_id,family,token_hash,access_hash,access_expires,expires_at) VALUES (?,?,?,?,?,now()-interval '1 minute',now()+interval '1 day')",sid,u,family,hash(token),hash("old-access"));IdentityService identity=new IdentityService(db,"test");Object first=identity.refresh(token,"test",request);Object retried=identity.refresh(token,"test",request);assertEquals(db.encode(first),db.encode(retried));assertThrows(ApiError.class,()->identity.refresh(token,"test",id()));assertEquals(0,db.jdbc.queryForObject("SELECT count(*) FROM refresh_sessions WHERE family=? AND NOT revoked",Integer.class,family));}
 @Test void refreshRetryCipherRequiresOriginalCredential(){String encrypted=RefreshRetryCipher.encrypt("secret","payload");assertEquals("payload",RefreshRetryCipher.decrypt("secret",encrypted));assertThrows(IllegalStateException.class,()->RefreshRetryCipher.decrypt("wrong",encrypted));}

 ludo.binge.LudoBingeServer.online.OnlineController leaderboardController(){return new ludo.binge.LudoBingeServer.online.OnlineController(db,rooms,new MatchService(db,false),queue,new IdentityService(db,"test"),"","");}
 @Test void leaderboardRanksBeyondTopFiftyAndDoesNotInventPercentiles(){
  UUID last=user();db.update("INSERT INTO player_stats VALUES (?,1,0,0)",last);
  for(int i=0;i<55;i++)db.update("INSERT INTO player_stats VALUES (?,2,1,0)",user());
  var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(new IdentityService.Actor(last,id()),null);
  var result=map(leaderboardController().leaderboard(auth,"all_time"));
  assertEquals(56,((Number)result.get("myRank")).intValue());assertEquals(56L,result.get("totalUsers"));
  assertEquals(50,((List<?>)result.get("items")).size());assertEquals(0L,result.get("resetSeconds"));
  assertEquals("You are doing better than 0% of other players!",result.get("summaryText"));
 }
 @Test void weeklyLeaderboardUsesOnlyCompletedMatchesThisUtcWeek(){
  UUID a=user(),b=user();var created=map(db.tx(()->rooms.create(a,2)));UUID room=uuid(map(created.get("room")).get("id"));
  String snapshot=db.encode(Map.of("players",List.of(Map.of("userId",a.toString(),"forfeited",false),Map.of("userId",b.toString(),"forfeited",true)),"winner",a.toString()));
  db.update("INSERT INTO matches(id,room_id,snapshot,status,deadline,finished_at) VALUES (?,?,?::jsonb,'FINISHED',now(),now())",id(),room,snapshot);
  db.update("INSERT INTO matches(id,room_id,snapshot,status,deadline,finished_at) VALUES (?,?,?::jsonb,'FINISHED',now(),now()-interval '8 days')",id(),room,snapshot);
  var result=map(leaderboardController().leaderboard(null,"weekly"));var items=(List<?>)result.get("items");
  assertEquals(2,items.size());assertEquals(110L,map(items.getFirst()).get("points"));assertEquals(1L,map(items.getFirst()).get("played"));
  assertEquals(0L,map(items.get(1)).get("points"));assertTrue(((Number)result.get("resetSeconds")).longValue()>0);
  assertThrows(ApiError.class,()->leaderboardController().leaderboard(null,"daily"));
 }
 @Test void leaderboardExcludesUnplayedAndInactiveUsers(){
  user();UUID inactive=user();db.update("INSERT INTO player_stats VALUES (?,10,10,0)",inactive);db.update("UPDATE users SET status='SUSPENDED' WHERE id=?",inactive);
  assertTrue(((List<?>)map(leaderboardController().leaderboard(null,"all_time")).get("items")).isEmpty());
 }
}
