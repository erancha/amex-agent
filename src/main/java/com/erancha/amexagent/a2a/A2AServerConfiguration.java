package com.erancha.amexagent.a2a;

import com.erancha.amexagent.auth.Callers;
import com.erancha.amexagent.auth.UserResolver;
import com.erancha.amexagent.auth.Users;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * Wires the a2a-java server library as Spring beans: the task store (in memory, or shared through
 * Redis when APP_TASKS_REDIS_URL is set), the queue manager (which closes streams when a task
 * waits for input), the main event bus and its processor thread, the two a2a thread pools, the
 * caller-secret filter, the user resolver and the JSON-RPC handler (wrapping the SDK's default
 * request handler) that the endpoints delegate to.
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
      @Qualifier("a2aInternalExecutor") Executor internal,
      @Qualifier("a2aEventConsumerExecutor") Executor eventConsumer) {
    RequestHandler requestHandler = DefaultRequestHandler.builder()
        .agentExecutor(agentExecutor)
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
