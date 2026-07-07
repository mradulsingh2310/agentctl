package io.agentctl.api.workflow;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

@Configuration
@ConditionalOnProperty(name = "agentctl.worker.enabled", havingValue = "true")
class TemporalWorkerConfiguration {
    @Bean
    TemporalWorkerLifecycle temporalWorkerLifecycle(
            WorkflowClient workflowClient,
            JdbcRunProjectionActivities activities,
            AgentStepActivities agentStepActivities,
            @Value("${agentctl.temporal.task-queue}") String taskQueue) {
        return new TemporalWorkerLifecycle(workflowClient, activities, agentStepActivities, taskQueue);
    }

    static class TemporalWorkerLifecycle implements SmartLifecycle {
        private final WorkerFactory workerFactory;
        private boolean running;

        TemporalWorkerLifecycle(
                WorkflowClient workflowClient,
                JdbcRunProjectionActivities activities,
                AgentStepActivities agentStepActivities,
                String taskQueue) {
            workerFactory = WorkerFactory.newInstance(workflowClient);
            Worker worker = workerFactory.newWorker(taskQueue);
            worker.registerWorkflowImplementationTypes(RunWorkflowImpl.class);
            worker.registerActivitiesImplementations(activities, agentStepActivities);
        }

        @Override
        public void start() {
            workerFactory.start();
            running = true;
        }

        @Override
        public void stop() {
            workerFactory.shutdown();
            workerFactory.awaitTermination(10, TimeUnit.SECONDS);
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }
}
