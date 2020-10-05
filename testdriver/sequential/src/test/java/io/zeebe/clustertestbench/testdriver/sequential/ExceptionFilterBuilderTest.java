package io.zeebe.clustertestbench.testdriver.sequential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE;

import java.util.function.Predicate;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.zeebe.clustertestbench.testdriver.sequential.ExceptionFilterBuilder.WorkflowNotFoundPredicate;

class ExceptionFilterBuilderTest {
	private static final String TEST_PROCESS_ID = "test-workflow";

	@Nested
	@DisplayName("predicate ressource exhausted")
	class ResourceExhaustedPredicateTest {

		private final Predicate<Throwable> sutPredicate = ExceptionFilterBuilder.RESSOURCE_EXHAUSTED_ERROR_PREDICATE;

		@Test
		void shouldNotMatchForArbitraryThrowables() {
			// given
			Throwable t = new Throwable();

			// when
			boolean match = sutPredicate.test(t);

			// then
			assertThat(match).isFalse();
		}

		@ParameterizedTest
		@EnumSource(mode = EXCLUDE, names = "RESOURCE_EXHAUSTED")
		void shouldNotMatchForStatusRuntimeExceptionsThatAreNotRessourceExhasueted(Code code) {
			// given
			Throwable t = createNestedStatusRuntimeException(code);

			// when
			boolean match = sutPredicate.test(t);

			// then
			assertThat(match).isFalse();
		}

		@Test
		void shouldMatchForNextedRessourceExhaustedException() {
			// given
			Throwable t = createNestedStatusRuntimeException(Code.RESOURCE_EXHAUSTED);

			// when
			boolean match = sutPredicate.test(t);

			// then
			assertThat(match).isTrue();
		}
	}

	@Nested
	@DisplayName("predicate workflow not found")
	class WorkflowNotFoundPredicateTest {

		private final Predicate<Throwable> sutPredicate = new WorkflowNotFoundPredicate(TEST_PROCESS_ID);

		@Test
		void shouldNotMatchForArbitraryThrowables() {
			// given
			Throwable t = new Throwable();

			// when
			boolean match = sutPredicate.test(t);

			// then
			assertThat(match).isFalse();
		}

		@Test
		void shouldMatchForDifferentWorkflowMentionedInException() {
			// given
			Throwable t = createNestedStatusRuntimeException(Code.NOT_FOUND,
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
			Throwable t = createNestedStatusRuntimeException(Code.NOT_FOUND,
					"Command rejected with code 'CREATE_WITH_AWAITING_RESULT': Expected to find workflow definition with process ID 'test-workflow', but none found");

			// when
			boolean match = sutPredicate.test(t);

			// then
			assertThat(match).isTrue();
		}

		@Test
		void shouldMatchForArbitrayCommands() {
			// given
			Throwable t = createNestedStatusRuntimeException(Code.NOT_FOUND,
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
			Throwable t = new Throwable();

			Predicate<Throwable> sutExceptionFilter = new ExceptionFilterBuilder().build();

			// when
			boolean actual = sutExceptionFilter.test(t);

			// then
			Assertions.assertThat(actual).isTrue();
		}

		@Test
		void shouldTestPositiveForRessourceExhaustedException() {
			// given
			Throwable t = createNestedStatusRuntimeException(Code.RESOURCE_EXHAUSTED);

			Predicate<Throwable> sutExceptionFilter = new ExceptionFilterBuilder().build();

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
			Throwable t = new Throwable();

			Predicate<Throwable> sutExceptionFilter = new ExceptionFilterBuilder().ignoreRessourceExhaustedExceptions()
					.build();

			// when
			boolean actual = sutExceptionFilter.test(t);

			// then
			Assertions.assertThat(actual).isTrue();
		}

		@Test
		void shouldTestNegativeForRessourceExhaustedException() {
			// given
			Throwable t = createNestedStatusRuntimeException(Code.RESOURCE_EXHAUSTED);

			Predicate<Throwable> sutExceptionFilter = new ExceptionFilterBuilder().ignoreRessourceExhaustedExceptions()
					.build();

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
			Throwable t = new Throwable();

			Predicate<Throwable> sutExceptionFilter = new ExceptionFilterBuilder()
					.ignoreWorkflowNotFoundExceptions(TEST_PROCESS_ID).ignoreRessourceExhaustedExceptions().build();

			// when
			boolean actual = sutExceptionFilter.test(t);

			// then
			Assertions.assertThat(actual).isTrue();
		}

		@Test
		void shouldTestNegativeForWorkflowNotFoundException() {
			// given
			Throwable t = createNestedStatusRuntimeException(Code.NOT_FOUND,
					"Command rejected with code 'CREATE_WITH_AWAITING_RESULT': Expected to find workflow definition with process ID 'test-workflow', but none found");

			Predicate<Throwable> sutExceptionFilter = new ExceptionFilterBuilder()
					.ignoreWorkflowNotFoundExceptions(TEST_PROCESS_ID).build();

			// when
			boolean actual = sutExceptionFilter.test(t);

			// then
			Assertions.assertThat(actual).isFalse();
		}
	}

	@Nested
	@DisplayName("When exception filter ignores workflow not found exceptions and ressource exhausted exceptions")
	class ErrorFilterWithCombinedPredicated {

		@Test
		void shouldTestPositiveForArbitraryException() {
			// given
			Throwable t = new Throwable();

			Predicate<Throwable> sutExceptionFilter = new ExceptionFilterBuilder().ignoreRessourceExhaustedExceptions()
					.ignoreWorkflowNotFoundExceptions(TEST_PROCESS_ID).ignoreRessourceExhaustedExceptions().build();

			// when
			boolean actual = sutExceptionFilter.test(t);

			// then
			Assertions.assertThat(actual).isTrue();
		}

		@Test
		void shouldTestNegativeForWorkflowNotFoundException() {
			// given
			Throwable t = createNestedStatusRuntimeException(Code.NOT_FOUND,
					"Command rejected with code 'CREATE_WITH_AWAITING_RESULT': Expected to find workflow definition with process ID 'test-workflow', but none found");

			Predicate<Throwable> sutExceptionFilter = new ExceptionFilterBuilder()
					.ignoreWorkflowNotFoundExceptions(TEST_PROCESS_ID).ignoreRessourceExhaustedExceptions().build();

			// when
			boolean actual = sutExceptionFilter.test(t);

			// then
			Assertions.assertThat(actual).isFalse();
		}
		
		@Test
		void shouldTestNegativeForResourceExhaustedException() {
			// given
			Throwable t = createNestedStatusRuntimeException(Code.RESOURCE_EXHAUSTED);

			Predicate<Throwable> sutExceptionFilter = new ExceptionFilterBuilder()
					.ignoreWorkflowNotFoundExceptions(TEST_PROCESS_ID).ignoreRessourceExhaustedExceptions().build();

			// when
			boolean actual = sutExceptionFilter.test(t);

			// then
			Assertions.assertThat(actual).isFalse();
		}
	}

	private static Throwable createNestedStatusRuntimeException(Code code) {
		return createNestedStatusRuntimeException(code, "dummy description");
	}

	private static Throwable createNestedStatusRuntimeException(Code code, String description) {
		StatusRuntimeException ressourceExhaustedException = new StatusRuntimeException(
				Status.fromCode(code).withDescription(description));

		Throwable t = new Throwable(ressourceExhaustedException);
		return t;
	}
}
