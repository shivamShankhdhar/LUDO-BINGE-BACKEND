package ludo.binge.LudoBingeServer;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import ludo.binge.LudoBingeServer.shared.Store;
import ludo.binge.LudoBingeServer.online.MatchmakingService;
import ludo.binge.LudoBingeServer.game.MatchService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.function.Predicate;
import static ludo.binge.LudoBingeServer.shared.Store.*;
import static org.junit.jupiter.api.Assertions.*;

/** Authenticated HTTP/SSE integration against an isolated disposable PostgreSQL database. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
 "spring.config.import=", "ludo.workers.enabled=false"
})
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.TestExecutionListeners(listeners=OnlineLiveFlowTests.Cleanup.class,
 mergeMode=org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class OnlineLiveFlowTests {
 public static class Cleanup extends org.springframework.test.context.support.AbstractTestExecutionListener {
  @Override public int getOrder(){return Integer.MIN_VALUE;}
  @Override public void afterTestClass(org.springframework.test.context.TestContext context)throws Exception{postgres.close();}
 }
 static final EmbeddedPostgres postgres;
 static { try { postgres=EmbeddedPostgres.builder().setPort(0).start(); }
 catch(IOException e){throw new ExceptionInInitializerError(e);} }
 @DynamicPropertySource static void database(DynamicPropertyRegistry registry){
  registry.add("spring.datasource.url",()->postgres.getJdbcUrl("postgres","postgres"));
  registry.add("spring.datasource.username",()->"postgres");
  registry.add("spring.datasource.password",()->"postgres");
 }
 @LocalServerPort int port;
 @Autowired Store db;
 record Player(UUID id,String token){}
 Player player(String name){
  UUID u=id();String access=token();
  db.update("INSERT INTO users(id,google_sub,display_name,connected_until) VALUES (?,?,?,now()+interval '5 minutes')",u,u.toString(),name);
  db.update("INSERT INTO refresh_sessions(id,user_id,family,token_hash,access_hash,access_expires,expires_at) VALUES (?,?,?,?,?,now()+interval '15 minutes',now()+interval '1 day')",id(),u,id(),hash(token()),hash(access));
  return new Player(u,access);
 }
 Map<?,?> map(Object value){return (Map<?,?>)value;}
 Map<?,?> call(Player player,String path,Object body)throws Exception{
  var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path))
   .timeout(java.time.Duration.ofSeconds(5)).header("Authorization","Bearer "+player.token());
  if(body!=null)builder.header("Content-Type","application/json").header("Idempotency-Key",id().toString()).POST(HttpRequest.BodyPublishers.ofString(db.encode(body)));
  try(var client=HttpClient.newHttpClient()){
   var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
   assertEquals(200,response.statusCode(),response.body());return db.decode(response.body(),Map.class);
  }
 }
 class Live implements AutoCloseable{
  final HttpURLConnection connection;final BufferedReader reader;
  Live(Player player)throws Exception{
   connection=(HttpURLConnection)URI.create("http://localhost:"+port+"/api/v1/live").toURL().openConnection();
   connection.setRequestProperty("Authorization","Bearer "+player.token());connection.setReadTimeout(5000);
   assertEquals(200,connection.getResponseCode());assertEquals("no",connection.getHeaderField("X-Accel-Buffering"));
   reader=new BufferedReader(new InputStreamReader(connection.getInputStream()));
  }
  Map<?,?> until(Predicate<Map<?,?>> predicate)throws Exception{
   long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
   while(System.nanoTime()<deadline){String line=reader.readLine();assertNotNull(line,"Live stream ended unexpectedly");
    if(line.startsWith("data:")){var snapshot=db.decode(line.substring(5).trim(),Map.class);if(predicate.test(snapshot))return snapshot;}
   }
   throw new AssertionError("Expected live transition was not delivered");
  }
  public void close()throws IOException{reader.close();connection.disconnect();}
 }
 @Test void privateHostSeesRequestAndGuestReceivesApprovalWithoutPolling()throws Exception{
  var host=player("Host");var guest=player("Guest");
  var created=call(host,"/rooms",Map.of("count",2));var room=map(created.get("room"));String roomId=room.get("id").toString();
  try(var hostLive=new Live(host);var guestLive=new Live(guest)){
   call(guest,"/rooms/join",Map.of("code",map(created.get("invite")).get("code")));
   var snapshot=hostLive.until(s->s.get("room") instanceof Map<?,?> r && r.get("joinRequests") instanceof List<?> requests&&!requests.isEmpty());
   var hostRoom=map(snapshot.get("room"));var request=map(((List<?>)hostRoom.get("joinRequests")).getFirst());
   assertEquals("Guest",request.get("display_name"));
   call(host,"/rooms/"+roomId+"/requests/"+request.get("id"),Map.of("revision",hostRoom.get("revision"),"accept",true));
   var joined=guestLive.until(s->s.get("room") instanceof Map<?,?> r && roomId.equals(r.get("id")));
   assertEquals(2,((List<?>)map(joined.get("room")).get("members")).size());
  }
  try(var reconnect=new Live(guest)){
   assertEquals(roomId,map(reconnect.until(s->s.containsKey("room")).get("room")).get("id"));
  }
 }
 @Test void publicPlayersReceiveRosterCountdownAndSameLiveMatch()throws Exception{
  var a=player("Alpha");var b=player("Beta");
  call(a,"/matchmaking/tickets",Map.of("count",2));call(b,"/matchmaking/tickets",Map.of("count",2));
  // Scheduled workers are disabled in this fixture; explicitly run the production worker.
  new MatchmakingService(db,new ludo.binge.LudoBingeServer.room.RoomService(db,""),true).tick();
  try(var aLive=new Live(a);var bLive=new Live(b)){
   var snapshot=aLive.until(s->s.get("queue") instanceof Map<?,?> q&&"RESERVED".equals(q.get("status")));
   var ticket=map(snapshot.get("queue"));var players=(List<?>)ticket.get("players");
   assertTrue(players.stream().map(this::map).anyMatch(p->"Beta".equals(p.get("displayName"))));
   var accept=Map.of("proposalId",map(ticket.get("proposal")).get("id"));
   call(a,"/matchmaking/accept",accept);call(b,"/matchmaking/accept",accept);call(a,"/matchmaking/accept",accept);
   var countdown=aLive.until(s->s.get("room") instanceof Map<?,?> r&&"COUNTDOWN".equals(r.get("phase")));
   String roomId=map(countdown.get("room")).get("id").toString();
   db.update("UPDATE rooms SET countdown_at=now()-interval '1 second' WHERE id=?",UUID.fromString(roomId));
   new MatchService(db,true).tick();
   var first=aLive.until(s->s.get("match") instanceof Map<?,?> m&&Boolean.TRUE.equals(m.get("active")));
   var second=bLive.until(s->s.get("match") instanceof Map<?,?> m&&Boolean.TRUE.equals(m.get("active")));
   assertEquals(map(first.get("match")).get("matchId"),map(second.get("match")).get("matchId"));
   var match=map(first.get("match"));
   call(a,"/matches/"+match.get("matchId")+"/commands",Map.of("action","ROLL","revision",match.get("revision")));
   Predicate<Map<?,?>> moved=s->s.get("match") instanceof Map<?,?> m&&((Number)m.get("revision")).longValue()>0;
   var aMove=map(aLive.until(moved).get("match"));var bMove=map(bLive.until(moved).get("match"));
   assertEquals(aMove.get("revision"),bMove.get("revision"));
   assertEquals(aMove.get("state"),bMove.get("state"));
   assertEquals(map(aMove.get("lastEvent")).get("dice"),map(bMove.get("lastEvent")).get("dice"));
  }
 }
}
