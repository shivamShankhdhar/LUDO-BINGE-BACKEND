package ludo.binge.LudoBingeServer.shared;
import java.util.*;
import java.util.function.Supplier;
import java.security.*;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@Component
public class Store {
 public final JdbcTemplate jdbc;
 public final ObjectMapper json;
 private final TransactionTemplate transactions;
 private final TransactionTemplate snapshots;
 public Store(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager) { this.jdbc=jdbc;this.json=json;transactions=new TransactionTemplate(manager);transactions.setTimeout(10);snapshots=new TransactionTemplate(manager);snapshots.setReadOnly(true);snapshots.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);snapshots.setTimeout(10); }
 public <T>T readSnapshot(Supplier<T> work) { return snapshots.execute(s->work.get()); }
 public <T>T tx(Supplier<T> work) { return transactions.execute(s->work.get()); }
 public List<Map<String,Object>> rows(String sql,Object...args) { return jdbc.queryForList(sql,args); }
 public Map<String,Object> one(String sql,Object...args) { var rows=rows(sql,args);ApiError.require(!rows.isEmpty(),404,"NOT_FOUND");return rows.getFirst(); }
 public int update(String sql,Object...args) { return jdbc.update(sql,args); }
 public String encode(Object value) { return json.writeValueAsString(value); }
 public <T>T decode(Object value,Class<T> type) { return json.readValue(value.toString(),type); }
 public static UUID id() { return UUID.randomUUID(); }
 public static UUID uuid(Object value) { return UUID.fromString(value.toString()); }
 public static String hash(String text) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
 public static String token() { byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
 public Object mutate(UUID user,UUID key,String action,Object body,Supplier<Object> work) {
  String hash=hash(action+"\n"+encode(body));
  return tx(()-> {
   jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",user+":"+key);
   var old=rows("SELECT * FROM api_receipts WHERE user_id=? AND key=?",user,key);
   if(!old.isEmpty()) { ApiError.require(old.getFirst().get("request_hash").equals(hash),409,"IDEMPOTENCY_CONFLICT");return decode(old.getFirst().get("result"),Object.class); }
   Object result=work.get(); update("INSERT INTO api_receipts(user_id,key,request_hash,result) VALUES (?,?,?,?::jsonb)",user,key,hash,encode(result));return result;
  });
 }
 public void event(UUID room,UUID user,String type,Object payload) {
  update("INSERT INTO outbox(room_id,user_id,payload) VALUES (?,?,?::jsonb)",room,user,encode(Map.of("protocolVersion",1,"type",type,"eventId",id(),"serverTimeMs",System.currentTimeMillis(),"payload",payload)));
 }
 public void limit(String key,int maximum,int seconds) {
  int count=jdbc.queryForObject("INSERT INTO rate_limits(key,window_at,count) VALUES (?,now(),1) ON CONFLICT(key) DO UPDATE SET count=CASE WHEN rate_limits.window_at < now()-make_interval(secs=>?) THEN 1 ELSE rate_limits.count+1 END, window_at=CASE WHEN rate_limits.window_at < now()-make_interval(secs=>?) THEN now() ELSE rate_limits.window_at END RETURNING count",Integer.class,key,seconds,seconds);
  ApiError.require(count<=maximum,429,"RATE_LIMITED");
 }
}
