/*
 * Copyright 2025 ByteChef
 *
 * Licensed under the ByteChef Enterprise license (the "Enterprise License");
 * you may not use this file except in compliance with the Enterprise License.
 */

package com.bytechef.ee.automation.workflow.alert.service;

import com.bytechef.ee.automation.workflow.alert.domain.WorkflowAlertRule;
import com.bytechef.ee.automation.workflow.alert.domain.WorkflowAlertRuleType;
import com.bytechef.ee.automation.workflow.alert.repository.WorkflowAlertRuleRepository;
import com.bytechef.platform.annotation.ConditionalOnEEVersion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @version ee
 *
 * @author Ivica Cardic
 */
@Service
@ConditionalOnEEVersion
@Transactional
public class WorkflowAlertRuleServiceImpl implements WorkflowAlertRuleService {

    private final WorkflowAlertRuleRepository workflowAlertRuleRepository;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public WorkflowAlertRuleServiceImpl(WorkflowAlertRuleRepository workflowAlertRuleRepository) {
        this.workflowAlertRuleRepository = workflowAlertRuleRepository;
    }

    @Override
    public WorkflowAlertRule create(WorkflowAlertRule workflowAlertRule) {
        return workflowAlertRuleRepository.save(workflowAlertRule);
    }

    @Override
    public void delete(long id) {
        workflowAlertRuleRepository.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public WorkflowAlertRule getWorkflowAlertRule(long id) {
        return workflowAlertRuleRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("WorkflowAlertRule not found with id: " + id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowAlertRule> getWorkflowAlertRules(long workspaceId) {
        return workflowAlertRuleRepository.findAllByWorkspaceIdOrderByNameAsc(workspaceId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowAlertRule> getEnabledWorkflowAlertRules(long workspaceId) {
        return workflowAlertRuleRepository.findAllByWorkspaceIdAndEnabledTrue(workspaceId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowAlertRule> getEnabledWorkflowAlertRules(WorkflowAlertRuleType ruleType) {
        return workflowAlertRuleRepository.findAllByRuleTypeAndEnabledTrue(ruleType.ordinal());
    }

    @Override
    public WorkflowAlertRule update(WorkflowAlertRule workflowAlertRule) {
        return workflowAlertRuleRepository.save(workflowAlertRule);
    }
}
