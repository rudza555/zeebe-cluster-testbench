package io.zeebe.clustertestbench.testdriver.sequential;

import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

import io.zeebe.client.ZeebeClient;
import io.zeebe.client.api.response.ActivatedJob;
import io.zeebe.client.api.worker.JobClient;
import io.zeebe.client.api.worker.JobHandler;
import io.zeebe.client.api.worker.JobWorker;
import io.zeebe.client.impl.oauth.OAuthCredentialsProvider;
import io.zeebe.client.impl.oauth.OAuthCredentialsProviderBuilder;
import io.zeebe.clustertestbench.testdriver.api.CamundaCloudAuthenticationDetails;
import io.zeebe.clustertestbench.testdriver.api.TestDriver;
import io.zeebe.clustertestbench.testdriver.api.TestReport;
import io.zeebe.clustertestbench.testdriver.impl.CamundaCLoudAuthenticationDetailsImpl;
import io.zeebe.clustertestbench.testdriver.impl.TestReportImpl;
import io.zeebe.clustertestbench.testdriver.impl.TestTimingContext;
import io.zeebe.model.bpmn.BpmnModelInstance;
import io.zeebe.workflow.generator.builder.SequenceWorkflowBuilder;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SequentialTestDriver implements TestDriver {

  private static final Logger LOGGER = LoggerFactory.getLogger(SequentialTestDriver.class);

  private static final DateTimeFormatter INSTANT_FORMATTER =
      DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
          .withLocale(Locale.US)
          .withZone(ZoneId.systemDefault());

  private static final String KEY_ITERATION = "iteration";
  private static final String KEY_WORKFLOW_INSTANCE = "workflowInstanceKey";
  private static final String KEY_START_TIME = "startTime";

  private static final String JOB_TYPE = "test-job";
  private static final String WORKFLOW_ID = "sequential-test-workflow";

  private final ZeebeClient client;
  private final SequentialTestParameters testParameters;

  public SequentialTestDriver(
      final CamundaCLoudAuthenticationDetailsImpl authenticationDetails,
      final SequentialTestParameters testParameters) {
    LOGGER.info("Creating Sequential Test Driver");
    final OAuthCredentialsProvider cred =
        buildCredentialsProvider(requireNonNull(authenticationDetails));

    client =
        ZeebeClient.newClientBuilder()
            .brokerContactPoint(authenticationDetails.getContactPoint())
            .credentialsProvider(cred)
            .build();

    this.testParameters = requireNonNull(testParameters);

    createAndDeploySequentialWorkflow();
  }

  private void createAndDeploySequentialWorkflow() {
    final SequenceWorkflowBuilder builder =
        new SequenceWorkflowBuilder(Optional.of(testParameters.getSteps()), Optional.of(JOB_TYPE));

    final BpmnModelInstance workflow = builder.buildWorkflow(WORKFLOW_ID);

    LOGGER.info("Deploying test workflow:" + WORKFLOW_ID);
    client.newDeployCommand().addWorkflowModel(workflow, WORKFLOW_ID + ".bpmn").send().join();
  }

  public TestReport runTest() {
    LOGGER.info("Starting Sequential Test ");

    try (final TestReportImpl testReport = new TestReportImpl(buildTestReportMetaData());
        final TestTimingContext overallTimingContext =
            new TestTimingContext(
                testParameters.getMaxTimeForCompleteTest(),
                "Test exceeded maximum time of " + testParameters.getMaxTimeForCompleteTest(),
                testReport::addFailure); ) {
      final Duration timeForIteration = testParameters.getMaxTimeForIteration();

      final JobWorker workerRegistration =
          client
              .newWorker()
              .jobType(JOB_TYPE)
              .handler(new MoveAlongJobHandler())
              .timeout(Duration.ofSeconds(10))
              .open();

      for (int i = 0; i < testParameters.getIterations(); i++) {
        try (final TestTimingContext iterationTimingContxt =
            new TestTimingContext(
                timeForIteration,
                "Iteration " + i + " exceeded maximum time of " + timeForIteration,
                testReport::addFailure)) {

          final var variables = new HashMap<String, Object>();
          variables.put(
              KEY_START_TIME, convertMillisToString(iterationTimingContxt.getStartTime()));
          variables.put(KEY_ITERATION, i);

          final var result =
              client
                  .newCreateInstanceCommand()
                  .bpmnProcessId(WORKFLOW_ID)
                  .latestVersion()
                  .variables(variables)
                  .withResult()
                  .requestTimeout(timeForIteration.multipliedBy(2))
                  .send()
                  .get();

          iterationTimingContxt.putMetaData(KEY_WORKFLOW_INSTANCE, result.getWorkflowInstanceKey());

        } catch (final Exception e) {
          final var exceptionFilter =
              new ExceptionFilterBuilder() //
                  .ignoreRessourceExhaustedExceptions() //
                  // can occur because deployment needs to be distributed to other partitions
                  .ignoreWorkflowNotFoundExceptions(WORKFLOW_ID) //
                  .build();

          if (exceptionFilter.test(e)) {
            testReport.addFailure(
                "Exception in iteration "
                    + i
                    + ":"
                    + e.getMessage()
                    + " caused by "
                    + ofNullable(e.getCause())
                        .map(Throwable::getMessage)
                        .orElse("[cuase is empty]"));
          } else {
            // repeat iteration
            i--;
          }
        }
      }

      workerRegistration.close();

      return testReport;
    } finally {
      client.close();
    }
  }

  private OAuthCredentialsProvider buildCredentialsProvider(
      final CamundaCloudAuthenticationDetails authenticationDetails) {
    if (authenticationDetails.getAuthorizationURL() == null) {
      return new OAuthCredentialsProviderBuilder()
          .audience(authenticationDetails.getAudience())
          .clientId(authenticationDetails.getClientId())
          .clientSecret(authenticationDetails.getClientSecret())
          .build();
    } else {
      return new OAuthCredentialsProviderBuilder()
          .authorizationServerUrl(authenticationDetails.getAuthorizationURL())
          .audience(authenticationDetails.getAudience())
          .clientId(authenticationDetails.getClientId())
          .clientSecret(authenticationDetails.getClientSecret())
          .build();
    }
  }

  private static String convertMillisToString(final long millis) {
    final Instant instant = Instant.ofEpochMilli(millis);

    return INSTANT_FORMATTER.format(instant);
  }

  private Map<String, Object> buildTestReportMetaData() {
    return Map.of("testParams", testParameters);
  }

  private static class MoveAlongJobHandler implements JobHandler {
    @Override
    public void handle(final JobClient client, final ActivatedJob job) {
      LOGGER.info(job.toString());
      client.newCompleteCommand(job.getKey()).send().join();
    }
  }
}
