package io.zeebe.clustertestbench.testdriver.sequential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE;

import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.zeebe.clustertestbench.testdriver.sequential.ExceptionFilterBuilder.WorkflowNotFoundPredicate;
import java.util.function.Predicate;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ExceptionFilterBuilderTest {
  private static final String TEST_PROCESS_ID = "test-workflow";

  @Nested
  @DisplayName("predicate ressource exhausted")
  class ResourceExhaustedPredicateTest {

    private final Predicate<Exception> sutPredicate =
        ExceptionFilterBuilder.RESSOURCE_EXHAUSTED_ERROR_PREDICATE;

    @Test
    void shouldNotMatchForArbitraryExceptions() {
      // given
      Exception t = new Exception();

      // when
      boolean match = sutPredicate.test(t);

      // then
      assertThat(match).isFalse();
    }

    @ParameterizedTest
    @EnumSource(mode = EXCLUDE, names = "RESOURCE_EXHAUSTED")
    void shouldNotMatchForStatusRuntimeExceptionsThatAreNotRessourceExhasueted(Code code) {
      // given
      Exception t = createNestedStatusRuntimeException(code);

      // when
      boolean match = sutPredicate.test(t);

      // then
      assertThat(match).isFalse();
    }

    @Test
    void shouldMatchForNextedRessourceExhaustedException() {
      // given
      Exception t = createNestedStatusRuntimeException(Code.RESOURCE_EXHAUSTED);

      // when
      boolean match = sutPredicate.test(t);

      // then
      assertThat(match).isTrue();
    }
  }

  @Nested
  @DisplayName("predicate workflow not found")
  class WorkflowNotFoundPredicateTest {

    private final Predicate<Exception> sutPredicate =
        new WorkflowNotFoundPredicate(TEST_PROCESS_ID);

    @Test
    void shouldNotMatchForArbitraryExceptions() {
      // given
      Exception t = new Exception();

      // when
      boolean match = sutPredicate.test(t);

      // then
      assertThat(match).isFalse();
    }

    @Test
    void shouldMatchForDifferentWorkflowMentionedInException() {
      // given
      Exception t =
          createNestedStatusRuntimeException(
              Code.NOT_FOUND,
              "Command rejected with code 'CREATE_WITH_AWAITING_RESULT': Expected to find workflow definition with process ID 'some-other-workflow', but none found");

      // when
      boolean match = sutPredicate.test(t);

      // then
      assertThat(match).isFalse();
    }

    @Test
    void shouldMatchForExampleException() {
      // given

      // message as was observed in production
      Exception t =
          createNestedStatusRuntimeException(
              Code.NOT_FOUND,
              "Command rejected with code 'CREATE_WITH_AWAITING_RESULT': Expected to find workflow definition with process ID 'test-workflow', but none found");

      // when
      boolean match = sutPredicate.test(t);

      // then
      assertThat(match).isTrue();
    }

    @Test
    void shouldMatchForArbitrayCommands() {
      // given
      Exception t =
          createNestedStatusRuntimeException(
              Code.NOT_FOUND,
              "Command rejected with code 'SOME_OTHER_COMMAND': Expected to find workflow definition with process ID 'test-workflow', but none found");

      // when
      boolean match = sutPredicate.test(t);

      // then
      assertThat(match).isTrue();
    }
  }

  @Nested
  @DisplayName("When default exception filter is used")
  class DefaultExceptionFilter {

    @Test
    void shouldTestPositiveForArbitraryException() {
      // given
      Exception t = new Exception();

      Predicate<Exception> sutExceptionFilter = new ExceptionFilterBuilder().build();

      // when
      boolean actual = sutExceptionFilter.test(t);

      // then
      Assertions.assertThat(actual).isTrue();
    }

    @Test
    void shouldTestPositiveForRessourceExhaustedException() {
      // given
      Exception t = createNestedStatusRuntimeException(Code.RESOURCE_EXHAUSTED);

      Predicate<Exception> sutExceptionFilter = new ExceptionFilterBuilder().build();

      // when
      boolean actual = sutExceptionFilter.test(t);

      // then
      Assertions.assertThat(actual).isTrue();
    }
  }

  @Nested
  @DisplayName("When exception filter ignores resource exhausted exceptions")
  class ResourceExhaustedIgnoringErrorFilter {

    @Test
    void shouldTestPositiveForArbitraryException() {
      // given
      Exception t = new Exception();

      Predicate<Exception> sutExceptionFilter =
          new ExceptionFilterBuilder().ignoreRessourceExhaustedExceptions().build();

      // when
      boolean actual = sutExceptionFilter.test(t);

      // then
      Assertions.assertThat(actual).isTrue();
    }

    @Test
    void shouldTestNegativeForRessourceExhaustedException() {
      // given
      Exception t = createNestedStatusRuntimeException(Code.RESOURCE_EXHAUSTED);

      Predicate<Exception> sutExceptionFilter =
          new ExceptionFilterBuilder().ignoreRessourceExhaustedExceptions().build();

      // when
      boolean actual = sutExceptionFilter.test(t);

      // then
      Assertions.assertThat(actual).isFalse();
    }
  }

  @Nested
  @DisplayName("When exception filter ignores workflow not found exceptions")
  class WorkflowNotFoundIgnoringErrorFilter {

    @Test
    void shouldTestPositiveForArbitraryException() {
      // given
      Exception t = new Exception();

      Predicate<Exception> sutExceptionFilter =
          new ExceptionFilterBuilder()
              .ignoreWorkflowNotFoundExceptions(TEST_PROCESS_ID)
              .ignoreRessourceExhaustedExceptions()
              .build();

      // when
      boolean actual = sutExceptionFilter.test(t);

      // then
      Assertions.assertThat(actual).isTrue();
    }

    @Test
    void shouldTestNegativeForWorkflowNotFoundException() {
      // given
      Exception t =
          createNestedStatusRuntimeException(
              Code.NOT_FOUND,
              "Command rejected with code 'CREATE_WITH_AWAITING_RESULT': Expected to find workflow definition with process ID 'test-workflow', but none found");

      Predicate<Exception> sutExceptionFilter =
          new ExceptionFilterBuilder().ignoreWorkflowNotFoundExceptions(TEST_PROCESS_ID).build();

      // when
      boolean actual = sutExceptionFilter.test(t);

      // then
      Assertions.assertThat(actual).isFalse();
    }
  }

  @Nested
  @DisplayName(
      "When exception filter ignores workflow not found exceptions and ressource exhausted exceptions")
  class ErrorFilterWithCombinedPredicated {

    @Test
    void shouldTestPositiveForArbitraryException() {
      // given
      Exception t = new Exception();

      Predicate<Exception> sutExceptionFilter =
          new ExceptionFilterBuilder()
              .ignoreRessourceExhaustedExceptions()
              .ignoreWorkflowNotFoundExceptions(TEST_PROCESS_ID)
              .ignoreRessourceExhaustedExceptions()
              .build();

      // when
      boolean actual = sutExceptionFilter.test(t);

      // then
      Assertions.assertThat(actual).isTrue();
    }

    @Test
    void shouldTestNegativeForWorkflowNotFoundException() {
      // given
      Exception t =
          createNestedStatusRuntimeException(
              Code.NOT_FOUND,
              "Command rejected with code 'CREATE_WITH_AWAITING_RESULT': Expected to find workflow definition with process ID 'test-workflow', but none found");

      Predicate<Exception> sutExceptionFilter =
          new ExceptionFilterBuilder()
              .ignoreWorkflowNotFoundExceptions(TEST_PROCESS_ID)
              .ignoreRessourceExhaustedExceptions()
              .build();

      // when
      boolean actual = sutExceptionFilter.test(t);

      // then
      Assertions.assertThat(actual).isFalse();
    }

    @Test
    void shouldTestNegativeForResourceExhaustedException() {
      // given
      Exception t = createNestedStatusRuntimeException(Code.RESOURCE_EXHAUSTED);

      Predicate<Exception> sutExceptionFilter =
          new ExceptionFilterBuilder()
              .ignoreWorkflowNotFoundExceptions(TEST_PROCESS_ID)
              .ignoreRessourceExhaustedExceptions()
              .build();

      // when
      boolean actual = sutExceptionFilter.test(t);

      // then
      Assertions.assertThat(actual).isFalse();
    }
  }

  private static Exception createNestedStatusRuntimeException(Code code) {
    return createNestedStatusRuntimeException(code, "dummy description");
  }

  private static Exception createNestedStatusRuntimeException(Code code, String description) {
    StatusRuntimeException ressourceExhaustedException =
        new StatusRuntimeException(Status.fromCode(code).withDescription(description));

    Exception t = new Exception(ressourceExhaustedException);
    return t;
  }
}
