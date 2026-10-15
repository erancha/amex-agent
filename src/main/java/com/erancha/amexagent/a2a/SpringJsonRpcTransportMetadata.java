package com.erancha.amexagent.a2a;

import org.a2aproject.sdk.server.TransportMetadata;
import org.a2aproject.sdk.spec.TransportProtocol;

/**
 * Declares to the SDK's card validator, through ServiceLoader, that this server speaks JSON-RPC.
 * Without it the validator rejects the card's JSONRPC interface as an unavailable transport.
 */
public class SpringJsonRpcTransportMetadata implements TransportMetadata {
  @Override
  public String getTransportProtocol() {
    return TransportProtocol.JSONRPC.asString();
  }
}
