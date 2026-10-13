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
    String uri=request.getRequestURI();
    if((uri.startsWith("/api/v1/auth/")&&!uri.equals("/api/v1/auth/logout"))||uri.equals("/api/v1/capabilities")||uri.equals("/api/v1/config")||uri.startsWith("/actuator/")||uri.equals("/error")||uri.startsWith("/r/")){
     chain.doFilter(request,response);
     return;
    }
    String header=request.getHeader("Authorization");
    String token=null;
    if(header!=null && header.startsWith("Bearer ")){
     token=header.substring(7);

    }
    if(token!=null){
     try{ApiError.require(token.length()<=512,401,"AUTHENTICATION_REQUIRED");var actor=identity.authenticate(token);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,List.of()));}
     catch(ApiError e){response.setStatus(401);response.setContentType("application/json");response.getWriter().write("{\"code\":\"AUTHENTICATION_REQUIRED\"}");return;}
    }
    chain.doFilter(request,response);
   }
  };
  return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).requestCache(c->c.disable())
   .addFilterBefore(bearer,UsernamePasswordAuthenticationFilter.class)
   .authorizeHttpRequests(a->a
    .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/v1/capabilities","/api/v1/config","/api/v1/leaderboard","/actuator/health","/actuator/health/**","/r/**").permitAll()
    .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC).permitAll()
    .requestMatchers("/api/v1/auth/logout").authenticated()
    .requestMatchers("/api/v1/auth/**","/error").permitAll()
    .requestMatchers("/api/v1/**","/ws","/ws/**").authenticated().anyRequest().denyAll())
   .exceptionHandling(e->e.authenticationEntryPoint((req,res,error)->{res.setStatus(401);res.setContentType("application/json");res.getWriter().write("{\"code\":\"AUTHENTICATION_REQUIRED\"}");}))
   .build();
 }
}
