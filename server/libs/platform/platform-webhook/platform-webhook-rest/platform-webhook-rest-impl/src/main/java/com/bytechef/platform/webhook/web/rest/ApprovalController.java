/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bytechef.platform.webhook.web.rest;

import com.bytechef.atlas.coordinator.annotation.ConditionalOnCoordinator;
import com.bytechef.platform.workflow.execution.ApprovalId;
import com.bytechef.platform.workflow.execution.facade.ApprovalInputException;
import com.bytechef.platform.workflow.execution.facade.JobResumeFacade;
import com.bytechef.platform.workflow.execution.facade.JobResumeFacade.JobResumeOutcome;
import com.bytechef.platform.workflow.execution.token.ApprovalTokens;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author Ivica Cardic
 * @deprecated This controller is deprecated and may be removed in a future version.
 */
@RestController
@CrossOrigin
@ConditionalOnCoordinator
@Deprecated
public class ApprovalController {

    private final ObjectProvider<ApprovalTokens> approvalTokensProvider;
    private final JobResumeFacade jobResumeFacade;

    @SuppressFBWarnings("EI")
    public ApprovalController(ObjectProvider<ApprovalTokens> approvalTokensProvider, JobResumeFacade jobResumeFacade) {
        this.approvalTokensProvider = approvalTokensProvider;
        this.jobResumeFacade = jobResumeFacade;
    }

    /**
     * Security Note: SPRING_CSRF_UNRESTRICTED_REQUEST_MAPPING - CSRF protection is intentionally disabled for this
     * endpoint. Approval links are sent via email with cryptographically secure tokens (UUID) embedded in the URL. The
     * token itself provides authentication, and this endpoint must be accessible without a session.
     */
    @SuppressFBWarnings("SPRING_CSRF_UNRESTRICTED_REQUEST_MAPPING")
    @RequestMapping(method = {
        RequestMethod.GET, RequestMethod.POST
    }, value = "/approvals/{id}")
    public ResponseEntity<?> approve(@PathVariable String id) {
        Optional<ApprovalId> approvalIdOptional = parseApprovalId(id);

        if (approvalIdOptional.isEmpty()) {
            return ResponseEntity.badRequest()
                .build();
        }

        JobResumeOutcome outcome;

        try {
            outcome = jobResumeFacade.resumeApproval(id);
        } catch (ApprovalInputException approvalInputException) {
            return invalidApprovalInput(approvalInputException);
        }

        return ResponseEntity.status(getStatus(outcome))
            .build();
    }

    private Optional<ApprovalId> parseApprovalId(String id) {
        ApprovalTokens approvalTokens = approvalTokensProvider.getIfAvailable();

        if (approvalTokens == null) {
            return Optional.empty();
        }

        Optional<String> innerTokenOptional = approvalTokens.parseSignedToken(id);

        if (innerTokenOptional.isEmpty()) {
            return Optional.empty();
        }

        try {
            return Optional.of(ApprovalId.parse(innerTokenOptional.get()));
        } catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
            return Optional.empty();
        }
    }

    private static HttpStatus getStatus(JobResumeOutcome outcome) {
        return switch (outcome) {
            case OK -> HttpStatus.NO_CONTENT;
            case INVALID_ID, STREAMING_NOT_ALLOWED -> HttpStatus.BAD_REQUEST;
            case GONE -> HttpStatus.GONE;
            case JOB_FAILED -> HttpStatus.UNPROCESSABLE_CONTENT;
            case NOT_YET_SUSPENDED -> HttpStatus.CONFLICT;
        };
    }

    private static ResponseEntity<ProblemDetail> invalidApprovalInput(ApprovalInputException approvalInputException) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, approvalInputException.getMessage());

        problemDetail.setProperty("errors", approvalInputException.getErrors());

        return ResponseEntity.badRequest()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(problemDetail);
    }
}
