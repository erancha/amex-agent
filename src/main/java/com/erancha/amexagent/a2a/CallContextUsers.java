package com.erancha.amexagent.a2a;

import com.erancha.amexagent.auth.Caller;
import com.erancha.amexagent.auth.UserResolver;
import com.erancha.amexagent.auth.Users;
import com.erancha.amexagent.auth.VerifiedUser;
import java.util.Map;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.transport.jsonrpc.context.JSONRPCContextKeys;

/**
 * Resolves the person of an A2A request from what CallContexts recorded: the caller the secret
 * filter admitted and the Authorization header that caller forwarded. The token must have been
 * issued to that caller's registered client.
 */
public class CallContextUsers implements UserResolver {
  private final Users users;

  public CallContextUsers(Users users) {
    this.users = users;
  }

  @Override
  public VerifiedUser resolve(RequestContext context) {
    if (!users.required()) {
      return VerifiedUser.ANONYMOUS;
    }
    Map<String, Object> state = context.getCallContext().getState();
    Caller caller = (Caller) state.get(CallContexts.CALLER_KEY);
    if (caller == null) {
      throw new IllegalStateException("An admitted A2A request carries its caller");
    }
    @SuppressWarnings("unchecked")
    Map<String, String> headers = (Map<String, String>) state.get(JSONRPCContextKeys.HEADERS_KEY);
    return users.onBehalfOf(caller, headers.get("Authorization"));
  }
}
