package kr.mujuthaek.api.common.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 무거운 조회 결과를 키별로 잠깐(ttl) 다시 쓰는 캐시. 비었거나 지났으면 이 요청이 계산하고, 그동안 같은 키를 부른 요청은 그 결과를 기다려요(동시에 불러도 계산은 한
 * 번). 계산이 실패하면 남기지 않고 다음 요청이 다시 해요.
 *
 * <p>관리자 화면처럼 여러 명이 같은 숫자를 자주 여는 곳에 써요(2026-10-29 부하 시험: 같은 집계를 매번 다시 돌려 DB가 포화됨).
 */
public final class RecentCache<K, V> {

  /** 계산 하나: 시작한 시각과 결과. */
  private record Entry<V>(Instant at, CompletableFuture<V> value) {}

  private final Duration ttl;
  private final Clock clock;
  private final Map<K, Entry<V>> entries = new ConcurrentHashMap<>();

  public RecentCache(Duration ttl, Clock clock) {
    this.ttl = ttl;
    this.clock = clock;
  }

  /** ttl 안에 시작한 계산이 있으면 그 결과(끝나지 않았으면 기다림), 없으면 load로 새로 계산해요. load의 실행 오류는 그대로 던져요. */
  public V get(K key, Supplier<V> load) {
    Instant now = clock.instant();
    var mine = new Entry<V>(now, new CompletableFuture<>());
    Entry<V> got =
        entries.compute(
            key, (k, old) -> old != null && old.at().plus(ttl).isAfter(now) ? old : mine);
    if (got == mine) {
      try {
        mine.value().complete(load.get());
      } catch (RuntimeException failure) {
        entries.remove(key, mine);
        mine.value().completeExceptionally(failure);
        throw failure;
      }
    }
    try {
      return got.value().join();
    } catch (CompletionException e) {
      if (e.getCause() instanceof RuntimeException cause) {
        throw cause;
      }
      throw e;
    }
  }

  /** 모두 비워요(바뀐 것을 바로 보여야 할 때: 검수 처리 직후 등). */
  public void clear() {
    entries.clear();
  }
}
