package com.erancha.amexagent.auth;

import com.erancha.amexagent.a2a.A2AProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires token verification from app.auth.* and the registered callers from app.a2a.*. */
@Configuration
public class AuthConfiguration {
  @Bean
  public Users users(AuthProperties auth, A2AProperties props) {
    return new Users(auth.verifier(), auth.clientId(), props.a2a().auth().required());
  }

  @Bean
  public Callers callers(A2AProperties props) {
    return new Callers(props.a2a().allowedCallers());
  }
}
