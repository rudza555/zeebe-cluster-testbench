package io.zeebe.clustertestbench.testdriver.sequential;

import static java.util.function.Predicate.not;

import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import java.util.function.Predicate;

public class ExceptionFilterBuilder {

  protected static final Predicate<Exception> RESSOURCE_EXHAUSTED_ERROR_PREDICATE =
      (t) -> {
        return t.getCause() instanceof StatusRuntimeException
            && ((StatusRuntimeException) t.getCause()).getStatus().getCode()
                == Code.RESOURCE_EXHAUSTED;
      };

  private Predicate<Exception> exceptionPredicate = (t) -> true;

  public ExceptionFilterBuilder() {}

  public ExceptionFilterBuilder ignoreRessourceExhaustedExceptions() {
    appendAndNotTerm(RESSOURCE_EXHAUSTED_ERROR_PREDICATE);

    return this;
  }

  public ExceptionFilterBuilder ignoreWorkflowNotFoundExceptions(final String workflowId) {
    final WorkflowNotFoundPredicate workflowNotFoundPredicate =
        new WorkflowNotFoundPredicate(workflowId);

    appendAndNotTerm(workflowNotFoundPredicate);
    return this;
  }

  private void appendAndNotTerm(final Predicate<Exception> term) {
    exceptionPredicate = exceptionPredicate.and(not(term));
  }

  /**
   * Returns a filter predicate; if the predicate tests positive, the exception is an error; if it
   * tests negative the exception can be ignored
   *
   * @return
   */
  public Predicate<Exception> build() {
    return exceptionPredicate;
  }

  static final class WorkflowNotFoundPredicate implements Predicate<Exception> {

    private final String workflowID;

    protected WorkflowNotFoundPredicate(final String workflowId) {
      this.workflowID = workflowId;
    }

    @Override
    public boolean test(final Exception t) {
      if (t.getCause() instanceof StatusRuntimeException) {
        final StatusRuntimeException sre = (StatusRuntimeException) t.getCause();

        final Status status = sre.getStatus();

        return status.getCode() == Code.NOT_FOUND && status.getDescription().contains(workflowID);
      } else {
        return false;
      }
    }
  }
}
