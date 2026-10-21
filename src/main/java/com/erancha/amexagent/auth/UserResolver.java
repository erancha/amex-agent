package com.erancha.amexagent.auth;

import org.a2aproject.sdk.server.agentexecution.RequestContext;

/** Names the person an A2A request is for, from the token its caller forwarded. */
public interface UserResolver {
  /**
   * @throws InvalidTokenException when the request carries no acceptable token; the message says
   *     why in words for the person
   */
  VerifiedUser resolve(RequestContext context);
}
