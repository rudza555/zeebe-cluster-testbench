package io.zeebe.clustertestbench.cloud;

import io.zeebe.clustertestbench.cloud.filter.BadRequestResponseFilter;
import io.zeebe.clustertestbench.cloud.oauth.OAuthInterceptor;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;

public class CloudAPIClientFactory {

  public CloudAPIClient createCloudAPIClient(
      final String cloudApiUrl,
      final String authenticationServerURL,
      final String audience,
      final String clientId,
      final String clientSecret) {

    final OAuthInterceptor oauthInterceptor =
        OAuthInterceptor.forServiceAccountAuthorization(
            authenticationServerURL, audience, clientId, clientSecret);

    final Client client =
        ClientBuilder.newBuilder()
            .register(oauthInterceptor)
            .register(BadRequestResponseFilter.class)
            .build();
    final WebTarget target = client.target(cloudApiUrl);
    final ResteasyWebTarget rtarget = (ResteasyWebTarget) target;
    return rtarget.proxy(CloudAPIClient.class);
  }
}
