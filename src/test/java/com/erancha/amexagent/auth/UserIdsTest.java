package com.erancha.amexagent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Verifies a user id is used as a folder name only when it cannot leave the statements root. */
class UserIdsTest {
  @Test
  void folderName_plainIds_areAccepted() {
    assertThat(UserIds.folderName("1000")).isEqualTo("1000");
    assertThat(UserIds.folderName("anonymous")).isEqualTo("anonymous");
    assertThat(UserIds.folderName("a-b_C9")).isEqualTo("a-b_C9");
    assertThat(UserIds.folderName("9".repeat(255))).hasSize(255);
  }

  @Test
  void folderName_pathLikeOrEmptyIds_areRefused() {
    for (String bad : new String[] {"", ".", "..", "../x", "a/b", "a\\b", "a b", "a.b",
        "9".repeat(256)}) {
      assertThatThrownBy(() -> UserIds.folderName(bad)).as(bad)
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("folder name");
    }
  }
}
