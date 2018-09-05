/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.indexlifecycle;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.Version;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.index.Index;
import org.elasticsearch.node.Node;
import org.elasticsearch.xpack.core.indexlifecycle.LifecycleAction;
import org.elasticsearch.xpack.core.indexlifecycle.OperationMode;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.core.indexlifecycle.IndexLifecycleMetadata;
import org.elasticsearch.xpack.core.indexlifecycle.LifecyclePolicy;
import org.elasticsearch.xpack.core.indexlifecycle.LifecyclePolicyMetadata;
import org.elasticsearch.xpack.core.indexlifecycle.LifecycleSettings;
import org.elasticsearch.xpack.core.indexlifecycle.MockAction;
import org.elasticsearch.xpack.core.indexlifecycle.MockStep;
import org.elasticsearch.xpack.core.indexlifecycle.Phase;
import org.elasticsearch.xpack.core.indexlifecycle.Step;
import org.elasticsearch.xpack.core.indexlifecycle.Step.StepKey;
import org.elasticsearch.xpack.core.indexlifecycle.TerminalPolicyStep;
import org.elasticsearch.xpack.indexlifecycle.IndexLifecycleRunnerTests.MockClusterStateActionStep;
import org.elasticsearch.xpack.indexlifecycle.IndexLifecycleRunnerTests.MockClusterStateWaitStep;
import org.junit.Before;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.elasticsearch.xpack.core.indexlifecycle.LifecyclePolicyTestsUtils.newTestLifecyclePolicy;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;

public class ExecuteStepsUpdateTaskTests extends ESTestCase {

    private static final StepKey firstStepKey = new StepKey("first_phase", "action_1", "step_1");
    private static final StepKey secondStepKey = new StepKey("first_phase", "action_1", "step_2");
    private static final StepKey thirdStepKey = new StepKey("first_phase", "action_1", "step_3");
    private static final StepKey invalidStepKey = new StepKey("invalid", "invalid", "invalid");
    private ClusterState clusterState;
    private PolicyStepsRegistry policyStepsRegistry;
    private String mixedPolicyName;
    private String allClusterPolicyName;
    private String invalidPolicyName;
    private Index index;
    private MockClusterStateActionStep firstStep;
    private MockClusterStateWaitStep secondStep;
    private MockClusterStateWaitStep allClusterSecondStep;
    private MockStep thirdStep;
    private Client client;
    private IndexLifecycleMetadata lifecycleMetadata;
    private String indexName;

    @Before
    public void prepareState() throws IOException {
        client = Mockito.mock(Client.class);
        Mockito.when(client.settings()).thenReturn(Settings.EMPTY);
        firstStep = new MockClusterStateActionStep(firstStepKey, secondStepKey);
        secondStep = new MockClusterStateWaitStep(secondStepKey, thirdStepKey);
        secondStep.setWillComplete(true);
        allClusterSecondStep = new MockClusterStateWaitStep(secondStepKey, TerminalPolicyStep.KEY);
        allClusterSecondStep.setWillComplete(true);
        thirdStep = new MockStep(thirdStepKey, null);
        mixedPolicyName = randomAlphaOfLengthBetween(5, 10);
        allClusterPolicyName = randomAlphaOfLengthBetween(1, 4);
        invalidPolicyName = randomAlphaOfLength(11);
        Phase mixedPhase = new Phase("first_phase", TimeValue.ZERO, Collections.singletonMap(MockAction.NAME,
            new MockAction(Arrays.asList(firstStep, secondStep, thirdStep))));
        Phase allClusterPhase = new Phase("first_phase", TimeValue.ZERO, Collections.singletonMap(MockAction.NAME,
            new MockAction(Arrays.asList(firstStep, allClusterSecondStep))));
        Phase invalidPhase = new Phase("invalid_phase", TimeValue.ZERO, Collections.singletonMap(MockAction.NAME,
            new MockAction(Arrays.asList(new MockClusterStateActionStep(firstStepKey, invalidStepKey)))));
        LifecyclePolicy mixedPolicy = newTestLifecyclePolicy(mixedPolicyName,
            Collections.singletonMap(mixedPhase.getName(), mixedPhase));
        LifecyclePolicy allClusterPolicy = newTestLifecyclePolicy(allClusterPolicyName,
            Collections.singletonMap(allClusterPhase.getName(), allClusterPhase));
        LifecyclePolicy invalidPolicy = newTestLifecyclePolicy(invalidPolicyName,
            Collections.singletonMap(invalidPhase.getName(), invalidPhase));
        Map<String, LifecyclePolicyMetadata> policyMap = new HashMap<>();
        policyMap.put(mixedPolicyName, new LifecyclePolicyMetadata(mixedPolicy, Collections.emptyMap()));
        policyMap.put(allClusterPolicyName, new LifecyclePolicyMetadata(allClusterPolicy, Collections.emptyMap()));
        policyMap.put(invalidPolicyName, new LifecyclePolicyMetadata(invalidPolicy, Collections.emptyMap()));
        policyStepsRegistry = new PolicyStepsRegistry(NamedXContentRegistry.EMPTY);

        indexName = randomAlphaOfLength(5);
        lifecycleMetadata = new IndexLifecycleMetadata(policyMap, OperationMode.RUNNING);
        setupIndexPolicy(mixedPolicyName);
    }

    private void setupIndexPolicy(String policyName) {
        // Reset the index to use the "allClusterPolicyName"
        IndexMetaData indexMetadata = IndexMetaData.builder(indexName)
            .settings(settings(Version.CURRENT)
                .put(LifecycleSettings.LIFECYCLE_NAME, policyName)
                .put(LifecycleSettings.LIFECYCLE_PHASE, "new")
                .put(LifecycleSettings.LIFECYCLE_ACTION, "init")
                .put(LifecycleSettings.LIFECYCLE_STEP, "init"))
            .numberOfShards(randomIntBetween(1, 5)).numberOfReplicas(randomIntBetween(0, 5)).build();
        index = indexMetadata.getIndex();
        MetaData metaData = MetaData.builder()
            .persistentSettings(settings(Version.CURRENT).build())
            .putCustom(IndexLifecycleMetadata.TYPE, lifecycleMetadata)
            .put(IndexMetaData.builder(indexMetadata))
            .build();
        String nodeId = randomAlphaOfLength(10);
        DiscoveryNode masterNode = DiscoveryNode.createLocal(settings(Version.CURRENT)
                .put(Node.NODE_MASTER_SETTING.getKey(), true).build(),
            new TransportAddress(TransportAddress.META_ADDRESS, 9300), nodeId);
        clusterState = ClusterState.builder(ClusterName.DEFAULT)
            .metaData(metaData)
            .nodes(DiscoveryNodes.builder().localNodeId(nodeId).masterNodeId(nodeId).add(masterNode).build())
            .build();
        policyStepsRegistry.update(clusterState, client, () -> 0L);
    }

    public void testExecuteAllUntilEndOfPhase() throws IOException {
        NamedXContentRegistry registry = new NamedXContentRegistry(
            Collections.singletonList(new NamedXContentRegistry.Entry(LifecycleAction.class, new ParseField(MockAction.NAME),
                (p) -> {
                    MockAction.parse(p);
                    return new MockAction(Arrays.asList(firstStep, allClusterSecondStep));
                })));
        policyStepsRegistry = new PolicyStepsRegistry(registry);
        setupIndexPolicy(allClusterPolicyName);

        Step startStep = policyStepsRegistry.getFirstStep(allClusterPolicyName);
        long now = randomNonNegativeLong();
        // test execute start till end of phase `new`
        ExecuteStepsUpdateTask task = new ExecuteStepsUpdateTask(allClusterPolicyName, index, startStep, policyStepsRegistry, () -> now);
        ClusterState newState = task.execute(clusterState);

        // Update the registry so the next phase's steps are loaded
        policyStepsRegistry.update(newState, client, () -> now);

        // verify that both the `new` phase was executed and the next phase is to begin
        StepKey currentStepKey = IndexLifecycleRunner.getCurrentStepKey(newState.metaData().index(index).getSettings());
        assertThat(currentStepKey, equalTo(firstStep.getKey()));
        // test execute all actions in same phase
        task = new ExecuteStepsUpdateTask(allClusterPolicyName, index, firstStep, policyStepsRegistry, () -> now);
        newState = task.execute(newState);
        policyStepsRegistry.update(newState, client, () -> now);

        assertThat(firstStep.getExecuteCount(), equalTo(1L));
        assertThat(allClusterSecondStep.getExecuteCount(), equalTo(1L));
        currentStepKey = IndexLifecycleRunner.getCurrentStepKey(newState.metaData().index(index).getSettings());
        assertThat(currentStepKey, equalTo(TerminalPolicyStep.KEY));
        assertNull(policyStepsRegistry.getStep(index, currentStepKey).getNextStepKey());
        assertThat(LifecycleSettings.LIFECYCLE_PHASE_TIME_SETTING.get(newState.metaData().index(index).getSettings()), equalTo(now));
        assertThat(LifecycleSettings.LIFECYCLE_ACTION_TIME_SETTING.get(newState.metaData().index(index).getSettings()), equalTo(now));
        assertThat(LifecycleSettings.LIFECYCLE_STEP_INFO_SETTING.get(newState.metaData().index(index).getSettings()), equalTo(""));
    }

    public void testNeverExecuteNonClusterStateStep() throws IOException {
        setStateToKey(thirdStepKey);
        Step startStep = policyStepsRegistry.getStep(index, thirdStepKey);
        long now = randomNonNegativeLong();
        ExecuteStepsUpdateTask task = new ExecuteStepsUpdateTask(mixedPolicyName, index, startStep, policyStepsRegistry, () -> now);
        assertThat(task.execute(clusterState), sameInstance(clusterState));
    }

    public void testExecuteUntilFirstNonClusterStateStep() throws IOException {
        setStateToKey(secondStepKey);
        Step startStep = policyStepsRegistry.getStep(index, secondStepKey);
        long now = randomNonNegativeLong();
        ExecuteStepsUpdateTask task = new ExecuteStepsUpdateTask(mixedPolicyName, index, startStep, policyStepsRegistry, () -> now);
        ClusterState newState = task.execute(clusterState);
        StepKey currentStepKey = IndexLifecycleRunner.getCurrentStepKey(newState.metaData().index(index).getSettings());
        assertThat(currentStepKey, equalTo(thirdStepKey));
        assertThat(firstStep.getExecuteCount(), equalTo(0L));
        assertThat(secondStep.getExecuteCount(), equalTo(1L));
        assertThat(LifecycleSettings.LIFECYCLE_PHASE_TIME_SETTING.get(newState.metaData().index(index).getSettings()), equalTo(-1L));
        assertThat(LifecycleSettings.LIFECYCLE_ACTION_TIME_SETTING.get(newState.metaData().index(index).getSettings()), equalTo(-1L));
        assertThat(LifecycleSettings.LIFECYCLE_STEP_INFO_SETTING.get(newState.metaData().index(index).getSettings()), equalTo(""));
    }

    public void testExecuteInvalidStartStep() throws IOException {
        // Unset the index's phase/action/step to simulate starting from scratch
        clusterState = ClusterState.builder(clusterState)
            .metaData(MetaData.builder(clusterState.metaData())
                .put(IndexMetaData.builder(clusterState.metaData().index(indexName))
                    .settings(Settings.builder().put(clusterState.metaData().index(indexName).getSettings())
                        .put(LifecycleSettings.LIFECYCLE_PHASE, (String) null)
                        .put(LifecycleSettings.LIFECYCLE_ACTION, (String) null)
                        .put(LifecycleSettings.LIFECYCLE_STEP, (String) null).build()))).build();
        policyStepsRegistry.update(clusterState, client, () -> 0);

        Step invalidStep = new MockClusterStateActionStep(firstStepKey, secondStepKey);
        long now = randomNonNegativeLong();
        ExecuteStepsUpdateTask task = new ExecuteStepsUpdateTask(invalidPolicyName, index, invalidStep, policyStepsRegistry, () -> now);
        ClusterState newState = task.execute(clusterState);
        assertSame(newState, clusterState);
    }

    public void testExecuteIncompleteWaitStepNoInfo() throws IOException {
        secondStep.setWillComplete(false);
        setStateToKey(secondStepKey);
        Step startStep = policyStepsRegistry.getStep(index, secondStepKey);
        long now = randomNonNegativeLong();
        ExecuteStepsUpdateTask task = new ExecuteStepsUpdateTask(mixedPolicyName, index, startStep, policyStepsRegistry, () -> now);
        ClusterState newState = task.execute(clusterState);
        StepKey currentStepKey = IndexLifecycleRunner.getCurrentStepKey(newState.metaData().index(index).getSettings());
        assertThat(currentStepKey, equalTo(secondStepKey));
        assertThat(firstStep.getExecuteCount(), equalTo(0L));
        assertThat(secondStep.getExecuteCount(), equalTo(1L));
        assertThat(LifecycleSettings.LIFECYCLE_PHASE_TIME_SETTING.get(newState.metaData().index(index).getSettings()), equalTo(-1L));
        assertThat(LifecycleSettings.LIFECYCLE_ACTION_TIME_SETTING.get(newState.metaData().index(index).getSettings()), equalTo(-1L));
        assertThat(LifecycleSettings.LIFECYCLE_STEP_INFO_SETTING.get(newState.metaData().index(index).getSettings()), equalTo(""));
    }

    public void testExecuteIncompleteWaitStepWithInfo() throws IOException {
        secondStep.setWillComplete(false);
        RandomStepInfo stepInfo = new RandomStepInfo(() -> randomAlphaOfLength(10));
        secondStep.expectedInfo(stepInfo);
        setStateToKey(secondStepKey);
        Step startStep = policyStepsRegistry.getStep(index, secondStepKey);
        long now = randomNonNegativeLong();
        ExecuteStepsUpdateTask task = new ExecuteStepsUpdateTask(mixedPolicyName, index, startStep, policyStepsRegistry, () -> now);
        ClusterState newState = task.execute(clusterState);
        StepKey currentStepKey = IndexLifecycleRunner.getCurrentStepKey(newState.metaData().index(index).getSettings());
        assertThat(currentStepKey, equalTo(secondStepKey));
        assertThat(firstStep.getExecuteCount(), equalTo(0L));
        assertThat(secondStep.getExecuteCount(), equalTo(1L));
        assertThat(LifecycleSettings.LIFECYCLE_PHASE_TIME_SETTING.get(newState.metaData().index(index).getSettings()), equalTo(-1L));
        assertThat(LifecycleSettings.LIFECYCLE_ACTION_TIME_SETTING.get(newState.metaData().index(index).getSettings()), equalTo(-1L));
        assertThat(LifecycleSettings.LIFECYCLE_STEP_INFO_SETTING.get(newState.metaData().index(index).getSettings()),
                equalTo(stepInfo.toString()));
    }

    public void testOnFailure() throws IOException {
        setStateToKey(secondStepKey);
        Step startStep = policyStepsRegistry.getStep(index, secondStepKey);
        long now = randomNonNegativeLong();
        ExecuteStepsUpdateTask task = new ExecuteStepsUpdateTask(mixedPolicyName, index, startStep, policyStepsRegistry, () -> now);
        Exception expectedException = new RuntimeException();
        ElasticsearchException exception = expectThrows(ElasticsearchException.class,
                () -> task.onFailure(randomAlphaOfLength(10), expectedException));
        assertEquals("policy [" + mixedPolicyName + "] for index [" + index.getName() + "] failed on step [" + startStep.getKey() + "].",
                exception.getMessage());
        assertSame(expectedException, exception.getCause());
    }

    private void setStateToKey(StepKey stepKey) throws IOException {
        clusterState = ClusterState.builder(clusterState)
            .metaData(MetaData.builder(clusterState.metaData())
                .put(IndexMetaData.builder(clusterState.metaData().index(indexName))
                    .settings(Settings.builder().put(clusterState.metaData().index(indexName).getSettings())
                        .put(LifecycleSettings.LIFECYCLE_PHASE, stepKey.getPhase())
                        .put(LifecycleSettings.LIFECYCLE_ACTION, stepKey.getAction())
                        .put(LifecycleSettings.LIFECYCLE_STEP, stepKey.getName()).build()))).build();
        policyStepsRegistry.update(clusterState, client, () -> 0);
    }
}