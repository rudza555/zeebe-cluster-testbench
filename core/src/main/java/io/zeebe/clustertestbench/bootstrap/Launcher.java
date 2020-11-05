package io.zeebe.clustertestbench.bootstrap;

import static com.google.api.client.googleapis.javanet.GoogleNetHttpTransport.newTrustedTransport;
import static com.google.api.client.json.jackson2.JacksonFactory.getDefaultInstance;
import static java.lang.Runtime.getRuntime;

import io.zeebe.clustertestbench.notification.SlackNotificationService;
import java.io.IOException;
import java.io.StringBufferInputStream;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.request.api.ApiTestRequest;
import com.slack.api.methods.response.api.ApiTestResponse;

import io.zeebe.client.ZeebeClient;
import io.zeebe.client.api.worker.JobHandler;
import io.zeebe.client.api.worker.JobWorker;
import io.zeebe.client.impl.oauth.OAuthCredentialsProvider;
import io.zeebe.client.impl.oauth.OAuthCredentialsProviderBuilder;
import io.zeebe.clustertestbench.cloud.CloudAPIClient;
import io.zeebe.clustertestbench.cloud.CloudAPIClientFactory;
import io.zeebe.clustertestbench.handler.CreateClusterInCamundaCloudHandler;
import io.zeebe.clustertestbench.handler.CreateGenerationInCamundaCloudHandler;
import io.zeebe.clustertestbench.handler.DeleteClusterInCamundaCloudHandler;
import io.zeebe.clustertestbench.handler.DeleteGenerationInCamundaCloudHandler;
import io.zeebe.clustertestbench.handler.GatherInformationAboutClusterInCamundaCloudHandler;
import io.zeebe.clustertestbench.handler.MapNamesToUUIDsWorker;
import io.zeebe.clustertestbench.handler.NotifyEngineersHandler;
import io.zeebe.clustertestbench.handler.QueryClusterStateInCamundaCloudHandler;
import io.zeebe.clustertestbench.handler.RecordTestResultHandler;
import io.zeebe.clustertestbench.handler.SequentialTestHandler;
import io.zeebe.clustertestbench.handler.WarmUpClusterHandler;
import io.zeebe.clustertestbench.internal.cloud.InternalCloudAPIClient;
import io.zeebe.clustertestbench.internal.cloud.InternalCloudAPIClientFactory;

public class Launcher {

	private static final Logger logger = LoggerFactory.getLogger(Launcher.class);

	private final Map<String, JobWorker> registeredJobWorkers = new HashMap<>();

	private final String testOrchestrationContactPoint;
	private final OAuthServiceAccountAuthenticationDetails testOrchestrationAuthenticatonDetails;

	private final String cloudApiUrl;
	private final OAuthServiceAccountAuthenticationDetails cloudApiAuthenticationDetails;

	private String sheetsApiKeyFileContent;
	private final String reportSheetID;

	private final String slackToken;
	private final String slackChannel;

	private final InternalCloudAPIClient internalCloudApiClient;

	public Launcher(String testOrchestrationContactPoint,
			OAuthServiceAccountAuthenticationDetails testOrchestrationAuthenticatonDetails, String cloudApiUrl,
			OAuthServiceAccountAuthenticationDetails cloudApiAuthenticationDetails, String internalCloudApiUrl,
			OAuthUserAccountAuthenticationDetails internalCloudApiAuthenticationDetails, String sheetsApiKeyfileContent,
			String reportSheetID, String slackToken, String slackChannel) {
		this.testOrchestrationContactPoint = testOrchestrationContactPoint;
		this.testOrchestrationAuthenticatonDetails = testOrchestrationAuthenticatonDetails;
		this.cloudApiUrl = cloudApiUrl;
		this.cloudApiAuthenticationDetails = cloudApiAuthenticationDetails;
		this.sheetsApiKeyFileContent = sheetsApiKeyfileContent;
		this.reportSheetID = reportSheetID;
		this.slackToken = slackToken;
		this.slackChannel = slackChannel;

		internalCloudApiClient = createInternalCloudApiClient(internalCloudApiUrl, internalCloudApiAuthenticationDetails);
	}

	public void launch() throws IOException {
		performSelfTest();

		final OAuthCredentialsProvider cred = buildCredentialsProvider();

		try (final ZeebeClient client = ZeebeClient.newClientBuilder().numJobWorkerExecutionThreads(50)
				.brokerContactPoint(testOrchestrationContactPoint).credentialsProvider(cred).build();) {

			try {
				boolean success = new WorkflowDeployer(client).deployWorkflowsInClasspathFolder("workflows");

				if (!success) {
					logger.warn("Deployment failed");
				}
			} catch (IOException e) {
				logger.error("Error while deploying workflow: " + e.getMessage(), e);
			}

			registerWorkers(client);

			getRuntime().addShutdownHook(new Thread("Close thread") {
				@Override
				public void run() {
					logger.info("Received shutdown signal");

					registeredJobWorkers.values().forEach(JobWorker::close);
				}
			});

			waitForInterruption();

			logger.info("About to complete normally");
		}
	}

	private void performSelfTest() {
		testConnectionToOrchestrationCluster();
		testConnectionToCloudApi();
		testConnectionToInternalCloudApi();
		testConnectionToSlack();
		testConnectionToGoogleSheets();
	}

	private void testConnectionToOrchestrationCluster() {
		final OAuthCredentialsProvider cred = buildCredentialsProvider();

		try (final ZeebeClient client = ZeebeClient.newClientBuilder().numJobWorkerExecutionThreads(50)
				.brokerContactPoint(testOrchestrationContactPoint).credentialsProvider(cred).build();) {
			client.newTopologyRequest().send().join();

			logger.info("Selftest - Successfully established connection to test orchestration cluster");
		} catch (Exception e) {
			logger.error("Selftest - Unable to establish connection to test orchestration cluster", e);
		}
	}

	private void testConnectionToCloudApi() {
		try {
			final OAuthServiceAccountAuthenticationDetails authenticationDetails = cloudApiAuthenticationDetails;

			final String aerverUrl = authenticationDetails.getServerURL();
			final String audience = authenticationDetails.getAudience();
			final String clientId = authenticationDetails.getClientId();
			final String clientSecret = authenticationDetails.getClientSecret();

			CloudAPIClient client = new CloudAPIClientFactory().createCloudAPIClient(cloudApiUrl, aerverUrl, audience,
					clientId, clientSecret);
			client.getParameters();

			logger.info("Selftest - Successfully established connection to cloud API");
		} catch (Exception e) {
			logger.error("Selftest - Unable to establish connection to cloud API", e);
		}
	}

	private void testConnectionToInternalCloudApi() {
		try {
			internalCloudApiClient.listGenerationInfos();

			logger.info("Selftest - Successfully established connection to internal cloud API");
		} catch (Exception e) {
			logger.error("Selftest - Unable to establish connection to internal cloud API", e);
		}
	}

	private InternalCloudAPIClient createInternalCloudApiClient(final String internalCloudApiUrl,
			final OAuthUserAccountAuthenticationDetails authenticationDetails) {
		final String serverUrl = authenticationDetails.getServerURL();
		final String audience = authenticationDetails.getAudience();
		final String clientId = authenticationDetails.getClientId();
		final String clientSecret = authenticationDetails.getClientSecret();
		final String username = authenticationDetails.getUsername();
		final String password = authenticationDetails.getPassword();

		InternalCloudAPIClient client = new InternalCloudAPIClientFactory().createCloudAPIClient(internalCloudApiUrl,
				serverUrl, audience, clientId, clientSecret, username, password);
		return client;
	}

	private void testConnectionToSlack() {
		Slack slack = Slack.getInstance();

		MethodsClient slackClient = slack.methods(slackToken);

		try {
			ApiTestResponse response = slackClient.apiTest(ApiTestRequest.builder().foo("test").build());

			String returnedFoo = response.getArgs().getFoo();

			if ("test".equalsIgnoreCase(returnedFoo)) {
				logger.info("Selftest - Successfully established connection to Slack");
			} else {
				logger.error("Selftest - Wrong respponse when establishing connection to Slack: " + returnedFoo);
			}
		} catch (Exception e) {
			logger.error("Selftest - Unable to establish connection to Slack", e);
		}
	}

	private void testConnectionToGoogleSheets() {
		try (var inputStream = new StringBufferInputStream(sheetsApiKeyFileContent)) {
			GoogleCredential credential = GoogleCredential.fromStream(inputStream)
					.createScoped(Collections.singleton(SheetsScopes.SPREADSHEETS));

			Sheets service = new Sheets.Builder(newTrustedTransport(), getDefaultInstance(), credential)
					.setApplicationName("Zeebe Cluster Testbench - Publish Test Results Worker").build();

			Sheets.Spreadsheets.Values.Get request = service.spreadsheets().values().get(reportSheetID, "Sheet1!A1:B1");

			request.execute();
			logger.info("Selftest - Successfully established connection to Google Sheets");
		} catch (Exception e) {
			logger.error("Selftest - Unable to establish connection to Google Sheets", e);
		}
	}

	private void registerWorkers(final ZeebeClient client) {
		final OAuthServiceAccountAuthenticationDetails authenticationDetails = cloudApiAuthenticationDetails;

		final String cloudApiAuthenticationServerUrl = authenticationDetails.getServerURL();
		final String cloudApiAudience = authenticationDetails.getAudience();
		final String cloudApiClientId = authenticationDetails.getClientId();
		final String cloudApiClientSecret = authenticationDetails.getClientSecret();

		registerWorker(
				client, "map-names-to-uuids-job", new MapNamesToUUIDsWorker(cloudApiUrl,
						cloudApiAuthenticationServerUrl, cloudApiAudience, cloudApiClientId, cloudApiClientSecret),
				Duration.ofSeconds(10));
		registerWorker(
				client, "create-zeebe-cluster-in-camunda-cloud-job", new CreateClusterInCamundaCloudHandler(cloudApiUrl,
						cloudApiAuthenticationServerUrl, cloudApiAudience, cloudApiClientId, cloudApiClientSecret),
				Duration.ofMinutes(1));
		registerWorker(client, "query-zeebe-cluster-state-in-camunda-cloud-job",
				new QueryClusterStateInCamundaCloudHandler(cloudApiUrl, cloudApiAuthenticationServerUrl,
						cloudApiAudience, cloudApiClientId, cloudApiClientSecret),
				Duration.ofSeconds(10));
		registerWorker(client, "gather-information-about-cluster-in-camunda-cloud-job",
				new GatherInformationAboutClusterInCamundaCloudHandler(cloudApiUrl, cloudApiAuthenticationServerUrl,
						cloudApiAudience, cloudApiClientId, cloudApiClientSecret),
				Duration.ofSeconds(10));
		registerWorker(client, "warm-up-cluster-job", new WarmUpClusterHandler(), Duration.ofMinutes(3));

		registerWorker(client, "run-sequential-test-job", new SequentialTestHandler(), Duration.ofMinutes(30));

		try {
			registerWorker(client, "record-test-result-job",
					new RecordTestResultHandler(sheetsApiKeyFileContent, reportSheetID), Duration.ofSeconds(10));
		} catch (IOException | GeneralSecurityException e) {
			logger.error("Exception while creating and registering worker for 'record-test-result-job'", e);
		}

		final var slackNotificationService = new SlackNotificationService(slackToken, slackChannel);
		registerWorker(client, "notify-engineers-job", new NotifyEngineersHandler(slackNotificationService), Duration.ofSeconds(10));
		registerWorker(
				client, "destroy-zeebe-cluster-in-camunda-cloud-job", new DeleteClusterInCamundaCloudHandler(cloudApiUrl,
						cloudApiAuthenticationServerUrl, cloudApiAudience, cloudApiClientId, cloudApiClientSecret),
				Duration.ofSeconds(10));

		registerWorker(client, "create-generation-in-camunda-cloud-job",
				new CreateGenerationInCamundaCloudHandler(internalCloudApiClient), Duration.ofSeconds(10));
		registerWorker(client, "delete-generation-in-camunda-cloud-job",
				new DeleteGenerationInCamundaCloudHandler(internalCloudApiClient), Duration.ofSeconds(10));
	}

	private void registerWorker(ZeebeClient client, String jobType, JobHandler jobHandler, Duration timeout) {
		logger.info("Registering job worker " + jobHandler.getClass().getSimpleName() + " for: " + jobType);

		final JobWorker workerRegistration = client.newWorker().jobType(jobType).handler(jobHandler).timeout(timeout)
				.open();

		registeredJobWorkers.put(jobType, workerRegistration);

		logger.info("Job worker opened and receiving jobs.");
	}

	private OAuthCredentialsProvider buildCredentialsProvider() {
		final OAuthServiceAccountAuthenticationDetails authenticationDetails = testOrchestrationAuthenticatonDetails;

		if (authenticationDetails.getServerURL() == null) {
			// use default server
			return new OAuthCredentialsProviderBuilder().audience(authenticationDetails.getAudience())
					.clientId(authenticationDetails.getClientId()).clientSecret(authenticationDetails.getClientSecret())
					.build();
		} else {
			return new OAuthCredentialsProviderBuilder().authorizationServerUrl(authenticationDetails.getServerURL())
					.audience(authenticationDetails.getAudience()).clientId(authenticationDetails.getClientId())
					.clientSecret(authenticationDetails.getClientSecret()).build();
		}
	}

	private static void waitForInterruption() {
		CountDownLatch countDownLatch = new CountDownLatch(1);
		try {
			countDownLatch.await();
		} catch (InterruptedException e) {
			logger.info(e.getMessage(), e);
		}
	}

}
