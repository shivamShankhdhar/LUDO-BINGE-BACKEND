package ludo.binge.LudoBingeServer.identity;
import ludo.binge.LudoBingeServer.shared.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.core.*;
import static ludo.binge.LudoBingeServer.shared.ApiError.require;
import static ludo.binge.LudoBingeServer.shared.Store.*;

@Service
public class IdentityService {
 public record Actor(UUID userId,UUID sessionId) implements java.security.Principal { public String getName(){return userId.toString();} }
 private final Store db;
 private final java.util.Set<String> clientIds;
 private volatile JwtDecoder google;
 public IdentityService(Store db,@Value("${ludo.google.client-id:}") String clientId) {
  this.db=db;
  this.clientIds=Arrays.stream(clientId.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toSet());
 }
 public boolean configured(){return !clientIds.isEmpty();}
 public Object challenge(String remote) {
  db.limit("auth:"+remote,20,60); String nonce=token();UUID id=id();
  db.update("INSERT INTO auth_challenges(id,nonce_hash,expires_at) VALUES (?,?,now()+interval '5 minutes')",id,hash(nonce));
  return Map.of("challengeId",id,"nonce",nonce,"expiresIn",300);
 }
 private Jwt verify(String token) {
  require(configured(),503,"GOOGLE_NOT_CONFIGURED");
  if(google==null) synchronized(this) { if(google==null) {
   var decoder=NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build();
   decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(),jwt -> {
    boolean valid=jwt.getAudience().stream().anyMatch(clientIds::contains) && Set.of("https://accounts.google.com","accounts.google.com").contains(jwt.getClaimAsString("iss"));
    return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
   }));google=decoder;
  }}
  try{return google.decode(token);}catch(Exception e){throw new ApiError(401,"INVALID_GOOGLE_TOKEN");}
 }
 public Object login(UUID challenge,String idToken,String remote) {
  db.limit("login:"+remote,20,60); Jwt jwt=verify(idToken);
  String nonce=jwt.getClaimAsString("nonce");require(nonce!=null&&jwt.getSubject()!=null,401,"INVALID_GOOGLE_TOKEN");
  return db.tx(()->{
   require(db.update("UPDATE auth_challenges SET consumed=true WHERE id=? AND nonce_hash=? AND NOT consumed AND expires_at>now()",challenge,hash(nonce))==1,401,"CHALLENGE_EXPIRED_OR_REPLAYED");
   String name=Optional.ofNullable(jwt.getClaimAsString("name")).orElse("Player");name=name.substring(0,Math.min(name.length(),80));
   String photo=jwt.getClaimAsString("picture");if(photo!=null&&(!photo.startsWith("https://")||photo.length()>2048))photo=null;
   UUID user=db.jdbc.queryForObject("INSERT INTO users(id,google_sub,display_name,avatar_url) VALUES (?,?,?,?) ON CONFLICT(google_sub) DO UPDATE SET display_name=excluded.display_name,avatar_url=excluded.avatar_url,avatar_version=users.avatar_version+1,updated_at=now() RETURNING id",UUID.class,id(),jwt.getSubject(),name,photo);
   require(db.one("SELECT status FROM users WHERE id=?",user).get("status").equals("ACTIVE"),403,"ACCOUNT_UNAVAILABLE");
   return issue(user,id());
  });
 }
 public Object guest(String name,String remote) {
  throw new ApiError(403,"GOOGLE_SIGN_IN_REQUIRED");
 }
 private Object issue(UUID user,UUID family) {
  require(!db.rows("SELECT 1 FROM users WHERE id=? AND google_sub IS NOT NULL AND status='ACTIVE'",user).isEmpty(),403,"GOOGLE_SIGN_IN_REQUIRED");
  String refresh=token(),access=token();UUID sid=id();
  db.update("INSERT INTO refresh_sessions(id,user_id,family,token_hash,access_hash,access_expires,expires_at) VALUES (?,?,?,?,?,now()+interval '15 minutes',now()+interval '90 days')",sid,user,family,hash(refresh),hash(access));
  return Map.of("accessToken",access,"refreshToken",refresh,"tokenType","Bearer","expiresIn",900,"user",profile(user));
 }
 public Object refresh(String token,String remote) {return refresh(token,remote,null);}
 public Object refresh(String token,String remote,UUID retryKey) {
  db.limit("refresh:"+remote,30,60);
  Object result=db.tx(()->{
   var rows=db.rows("SELECT * FROM refresh_sessions WHERE token_hash=? AND expires_at>now() FOR UPDATE",hash(token));
   if(rows.isEmpty())return null;var old=rows.getFirst();
   if((boolean)old.get("consumed")&&!(boolean)old.get("revoked")&&retryKey!=null&&retryKey.equals(old.get("retry_key"))&&old.get("retry_until")!=null&&((java.sql.Timestamp)old.get("retry_until")).toInstant().isAfter(java.time.Instant.now()))return db.decode(RefreshRetryCipher.decrypt(token,old.get("retry_response").toString()),Object.class);
   if((boolean)old.get("consumed")||(boolean)old.get("revoked")) {
    db.update("UPDATE refresh_sessions SET revoked=true WHERE family=?",old.get("family"));return null;
   }
   db.update("UPDATE refresh_sessions SET consumed=true,access_hash=NULL WHERE id=?",old.get("id"));
   Object issued=issue(uuid(old.get("user_id")),uuid(old.get("family")));
   if(retryKey!=null)db.update("UPDATE refresh_sessions SET retry_key=?,retry_response=?,retry_until=now()+interval '30 seconds' WHERE id=?",retryKey,RefreshRetryCipher.encrypt(token,db.encode(issued)),old.get("id"));
   return issued;
  });
  require(result!=null,401,"REFRESH_EXPIRED_OR_REUSED");return result;
 }
 public Actor authenticate(String access) {
  require(access!=null&&access.length()<=512,401,"AUTHENTICATION_REQUIRED");
  var rows=db.rows("SELECT s.id,s.user_id FROM refresh_sessions s JOIN users u ON u.id=s.user_id WHERE access_hash=? AND NOT revoked AND access_expires>now() AND u.status='ACTIVE' AND u.google_sub IS NOT NULL",hash(access));
  require(!rows.isEmpty(),401,"AUTHENTICATION_REQUIRED");return new Actor(uuid(rows.getFirst().get("user_id")),uuid(rows.getFirst().get("id")));
 }
 public boolean valid(Actor actor){return !db.rows("SELECT 1 FROM refresh_sessions s JOIN users u ON u.id=s.user_id WHERE s.id=? AND s.user_id=? AND NOT revoked AND access_hash IS NOT NULL AND access_expires>now() AND u.status='ACTIVE' AND u.google_sub IS NOT NULL",actor.sessionId(),actor.userId()).isEmpty();}
 public Object profile(UUID user){return db.one("SELECT id AS \"userId\",display_name AS \"displayName\",avatar_url AS \"avatarUrl\",avatar_version AS \"avatarVersion\" FROM users WHERE id=?",user);}
 public Object logout(Actor actor){db.update("UPDATE refresh_sessions SET revoked=true WHERE family=(SELECT family FROM refresh_sessions WHERE id=?)",actor.sessionId());return Map.of("loggedOut",true);}
}
