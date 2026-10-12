package ludo.binge.LudoBingeServer.config;
import java.util.List;
import ludo.binge.LudoBingeServer.identity.IdentityService;
import ludo.binge.LudoBingeServer.shared.ApiError;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
@Configuration
public class SecurityConfiguration {
 @Bean SecurityFilterChain apiSecurity(HttpSecurity http,IdentityService identity)throws Exception {
  var bearer=new OncePerRequestFilter(){
   protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
    String header=request.getHeader("Authorization");
    if(header!=null){
     try{ApiError.require(header.startsWith("Bearer "),401,"AUTHENTICATION_REQUIRED");var actor=identity.authenticate(header.substring(7));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,List.of()));}
     catch(ApiError e){response.setStatus(401);response.setContentType("application/json");response.getWriter().write("{\"code\":\"AUTHENTICATION_REQUIRED\"}");return;}
    }
    chain.doFilter(request,response);
   }
  };
  return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).requestCache(c->c.disable())
   .addFilterBefore(bearer,UsernamePasswordAuthenticationFilter.class)
   .authorizeHttpRequests(a->a
    .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/v1/capabilities","/api/v1/config","/actuator/health","/actuator/health/**","/r/**").permitAll()
    .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/auth/guest","/api/v1/auth/challenge","/api/v1/auth/google","/api/v1/auth/refresh").permitAll()
    .requestMatchers("/api/v1/**","/ws").authenticated().anyRequest().denyAll())
   .exceptionHandling(e->e.authenticationEntryPoint((req,res,error)->{res.setStatus(401);res.setContentType("application/json");res.getWriter().write("{\"code\":\"AUTHENTICATION_REQUIRED\"}");}))
   .build();
 }
}
