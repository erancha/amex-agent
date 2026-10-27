package com.erancha.amexagent.a2a;

import com.erancha.amexagent.auth.Callers;
import com.erancha.amexagent.auth.UserResolver;
import com.erancha.amexagent.auth.Users;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.events.InMemoryQueueManager;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.events.MainEventBusProcessor;
import org.a2aproject.sdk.server.events.QueueManager;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.InMemoryPushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.PushNotificationSender;
import org.a2aproject.sdk.server.tasks.TaskStateProvider;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.transport.jsonrpc.handler.JSONRPCHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the a2a-java server library as Spring beans: the task store and the task lease (in memory,
 * or shared through Redis when APP_TASKS_REDIS_URL is set, with the lease sweep beside them), the
 * queue manager (which closes streams when a task waits for input), the main event bus and its
 * processor thread, the a2a thread pools, the caller-secret filter, the user resolver and the
 * JSON-RPC handler (wrapping the SDK's default request handler, whose agent executor runs under
 * the task's lease) that the endpoints delegate to.
 */
@Configuration
public class A2AServerConfiguration {
  private static final String REDIS_URL_SET = "'${app.tasks.redis-url:}' != ''";

  /** Without Redis: tasks live in this process's memory and are lost when it stops. */
  @Bean
  @ConditionalOnExpression("!(" + REDIS_URL_SET + ")")
  public InMemoryTaskStore inMemoryTaskStore() {
    return new InMemoryTaskStore();
  }

  /** With Redis: whichever replica receives a continue finds the task another replica paused. */
  @Bean
  @ConditionalOnExpression(REDIS_URL_SET)
  public RedisTaskStore redisTaskStore(A2AProperties properties) {
    return new RedisTaskStore(properties.tasks().redisUrl(), properties.tasks().ttl());
  }

  /** Without Redis: one process, so a lease in its memory keeps two calls off one task. */
  @Bean
  @ConditionalOnExpression("!(" + REDIS_URL_SET + ")")
  public InMemoryTaskLease inMemoryTaskLease(A2AProperties properties) {
    return new InMemoryTaskLease(properties.tasks().lease());
  }

  /** With Redis: a lease any replica sees, so no two replicas run one task. */
  @Bean
  @ConditionalOnExpression(REDIS_URL_SET)
  public RedisTaskLease redisTaskLease(A2AProperties properties) {
    return new RedisTaskLease(properties.tasks().redisUrl(), properties.tasks().lease());
  }

  /**
   * With Redis only: without it, tasks live and die with the one process, so no task can be left
   * by another replica.
   */
  @Bean
  @ConditionalOnExpression(REDIS_URL_SET)
  public LeaseSweep leaseSweep(RedisTaskStore store, RedisTaskLease lease,
      A2AProperties properties) {
    LeaseSweep sweep = new LeaseSweep(store, lease);
    sweep.start(properties.tasks().lease());
    return sweep;
  }

  @Bean
  public LeaseOwners leaseOwners() {
    return new LeaseOwners();
  }

  @Bean
  public MainEventBus mainEventBus() {
    return new MainEventBus();
  }

  @Bean
  public QueueManager queueManager(TaskStateProvider taskStore, MainEventBus bus) {
    return new InMemoryQueueManager(taskStore, bus, new InterruptedStateStreamHook());
  }

  @Bean
  public MainEventBusProcessor mainEventBusProcessor(MainEventBus bus, TaskStore taskStore,
      QueueManager queueManager) {
    // Push notifications are disabled on the card and in the handler, so the sender has nothing
    // to deliver; the processor still calls it once per streaming event, hence the no-op.
    PushNotificationSender noPush = (event, taskSnapshot) -> {};
    return new MainEventBusProcessor(bus, taskStore, noPush, queueManager);
  }

  @Bean
  @ConditionalOnProperty(name = "app.a2a.auth.required", havingValue = "true",
      matchIfMissing = true)
  public CallerSecretFilter callerSecretFilter(Callers callers) {
    return new CallerSecretFilter(callers);
  }

  @Bean
  public UserResolver userResolver(Users users) {
    return new CallContextUsers(users);
  }

  /**
   * Runs agent executions and stream conversion. The bounded queue lets the pool grow from 5
   * towards 50 threads only under load. Both a2a pools are non-default candidates so they are
   * injected by qualifier alone and Spring Boot still creates its own application task executor.
   */
  @Bean(defaultCandidate = false)
  public ExecutorService a2aInternalExecutor() {
    return new ThreadPoolExecutor(5, 50, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100),
        Thread.ofPlatform().name("a2a-agent-", 0).daemon(true).factory());
  }

  /** Renews the leases of the tasks this replica works on; each renewal is one short call. */
  @Bean(defaultCandidate = false)
  public ScheduledExecutorService leaseRenewals() {
    return Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("lease-renewal").daemon(true).factory());
  }

  /** Each open stream blocks one thread polling its event queue, so this pool is unbounded. */
  @Bean(defaultCandidate = false)
  public ExecutorService a2aEventConsumerExecutor() {
    return Executors.newCachedThreadPool(
        Thread.ofPlatform().name("a2a-event-consumer-", 0).daemon(true).factory());
  }

  /**
   * The request handler is built here rather than exposed as a bean: DefaultRequestHandler
   * carries CDI field injection and a PostConstruct hook that Spring would honour, autowiring a
   * config provider that does not exist here and overwriting the builder's settings.
   */
  @Bean
  public JSONRPCHandler jsonRpcHandler(AgentCard card, AgentExecutor agentExecutor,
      TaskStore taskStore, QueueManager queueManager, MainEventBusProcessor processor,
      TaskLease lease, A2AProperties properties,
      @Qualifier("leaseRenewals") ScheduledExecutorService leaseRenewals,
      @Qualifier("a2aInternalExecutor") Executor internal,
      @Qualifier("a2aEventConsumerExecutor") Executor eventConsumer) {
    RequestHandler requestHandler = DefaultRequestHandler.builder()
        .agentExecutor(new LeasedAgentExecutor(agentExecutor, lease, properties.tasks().lease(),
            leaseRenewals))
        .taskStore(taskStore)
        .queueManager(queueManager)
        .pushConfigStore(new InMemoryPushNotificationConfigStore())
        .mainEventBusProcessor(processor)
        .executor(internal)
        .eventConsumerExecutor(eventConsumer)
        // Callers are admitted by CallerSecretFilter and the person is checked by the executor,
        // which also binds a waiting task to its person; the SDK's ownership layer stays off so
        // there is one set of rules, not two.
        .authorizationRequired(false)
        .pushNotificationsEnabled(false)
        .build();
    return new JSONRPCHandler(card, requestHandler, internal);
  }
}
