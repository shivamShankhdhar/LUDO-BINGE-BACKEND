package ludo.binge.LudoBingeServer;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
 "spring.config.import=", "ludo.workers.enabled=false",
 "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unavailable_test_database",
 "spring.datasource.username=test",
 "spring.datasource.password=test",
 "spring.datasource.hikari.initialization-fail-timeout=-1",
 "spring.datasource.hikari.minimum-idle=0",
 "spring.datasource.hikari.connection-timeout=250",
 "spring.flyway.enabled=false"
})
class LudoBingeServerApplicationTests {
 @LocalServerPort int port;
 HttpResponse<String> get(String path) throws Exception {
  return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
 }
 @Test void exposesRealOnlineCapabilities() throws Exception {
  var response=get("/api/v1/capabilities");
  assertEquals(200,response.statusCode());
  assertTrue(response.body().contains("\"onlinePlayEnabled\":true"));
 }
 @Test void deniesPrivateRoutesWithoutSession() throws Exception {
  var response=get("/api/v1/profile");
  assertEquals(401,response.statusCode());
  assertTrue(response.headers().firstValue("set-cookie").isEmpty());
  assertEquals(401,get("/actuator/info").statusCode());
 }
 @Test void readinessFailsWhenPostgresIsUnavailable() throws Exception {
  assertEquals(503,get("/actuator/health/readiness").statusCode());
 }
 @Test void livenessIsIndependentOfPostgres() throws Exception {
  assertEquals(200,get("/actuator/health/liveness").statusCode());
 }
}
