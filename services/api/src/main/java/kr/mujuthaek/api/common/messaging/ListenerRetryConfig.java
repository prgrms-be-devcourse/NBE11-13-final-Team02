package kr.mujuthaek.api.common.messaging;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 받기 재시도(application.yml spring.rabbitmq.listener.simple.retry)는 연결 오류처럼 잠깐 생긴 오류에만 해요. 계약에 맞지 않아
 * 던진 AmqpRejectAndDontRequeueException은 다시 해도 같아서 바로 실패 큐로 보내요. 재시도하면 2초·4초·8초 동안 같은 큐의 다음 메시지가
 * 기다려요.
 */
@Configuration
class ListenerRetryConfig {

  @Bean
  RabbitListenerRetrySettingsCustomizer doNotRetryPermanentFailures() {
    return settings -> settings.setExceptionPredicate(ListenerRetryConfig::retryable);
  }

  static boolean retryable(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof AmqpRejectAndDontRequeueException) {
        return false;
      }
    }
    return true;
  }
}
