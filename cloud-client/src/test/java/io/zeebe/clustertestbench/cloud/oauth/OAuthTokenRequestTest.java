package io.zeebe.clustertestbench.cloud.oauth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class OAuthTokenRequestTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  void testSerialization() throws JsonProcessingException {
    // given
    final OAuthServiceAccountTokenRequest request =
        new OAuthServiceAccountTokenRequest(
            "<audience>", "clientId>", "<clientSecret>", "<grantType>");

    // when
    final String actual = OBJECT_MAPPER.writeValueAsString(request);

    // then
    Assertions.assertThat(actual)
        .isEqualTo(
            "{\"audience\":\"<audience>\",\"client_id\":\"clientId>\",\"client_secret\":\"<clientSecret>\",\"grant_type\":\"<grantType>\"}");
  }
}
