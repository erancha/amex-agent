package com.erancha.amexagent.auth;

import java.util.regex.Pattern;

/**
 * A Google account id doubles as the name of the person's statements folder, so only ids that are
 * plain path segments are accepted. Google issues digits; the test issuer may use letters.
 */
public final class UserIds {
  private static final Pattern FOLDER_NAME = Pattern.compile("[A-Za-z0-9_-]{1,255}");

  private UserIds() {}

  public static String folderName(String sub) {
    if (!FOLDER_NAME.matcher(sub).matches()) {
      throw new IllegalArgumentException("User id is not a plain folder name: '" + sub + "'");
    }
    return sub;
  }
}
