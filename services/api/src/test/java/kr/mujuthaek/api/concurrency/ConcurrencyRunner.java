package kr.mujuthaek.api.concurrency;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import tools.jackson.databind.json.JsonMapper;

/**
 * 동시성 시험 도구 (perf/concurrency/run.sh가 CONCURRENCY=1로 돌려요). 스레드 N개를 모두 준비시킨 뒤 CountDownLatch로 한꺼번에
 * 풀고, 결과(반환값 이름 또는 예외 이름)를 세요. 결과는 CONCURRENCY_OUT 폴더에 JSON으로 남겨요.
 */
final class ConcurrencyRunner {

  static final int THREADS =
      Integer.parseInt(System.getenv().getOrDefault("CONCURRENCY_THREADS", "100"));
  static final int ROUNDS =
      Integer.parseInt(System.getenv().getOrDefault("CONCURRENCY_ROUNDS", "10"));

  private ConcurrencyRunner() {}

  /** i번째 스레드가 할 일. 돌려준 값의 문자열이 결과, 예외면 예외 클래스 이름이 결과예요. */
  static Map<String, Integer> runAtOnce(IntFunction<Object> task) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    CountDownLatch ready = new CountDownLatch(THREADS);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<String>> futures = new ArrayList<>();
    for (int i = 0; i < THREADS; i++) {
      int n = i;
      futures.add(
          pool.submit(
              () -> {
                ready.countDown();
                go.await();
                try {
                  return String.valueOf(task.apply(n));
                } catch (RuntimeException e) {
                  Throwable root = e;
                  while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                  String msg = root.getMessage() == null ? "" : root.getMessage();
                  java.util.regex.Matcher m =
                      java.util.regex.Pattern.compile("constraint \"([^\"]+)\"").matcher(msg);
                  return "예외:"
                      + root.getClass().getSimpleName()
                      + (m.find() ? "(" + m.group(1) + ")" : "");
                }
              }));
    }
    ready.await();
    go.countDown();
    Map<String, Integer> outcomes = new TreeMap<>();
    for (Future<String> f : futures) {
      outcomes.merge(f.get(60, TimeUnit.SECONDS), 1, Integer::sum);
    }
    pool.shutdown();
    return outcomes;
  }

  /** 시나리오 결과를 CONCURRENCY_OUT/<이름>.json으로 (폴더가 없으면 남기지 않아요). */
  static void write(String name, Object result) throws Exception {
    String out = System.getenv("CONCURRENCY_OUT");
    if (out == null || out.isBlank()) return;
    Path dir = Path.of(out);
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve(name + ".json"),
        JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(result));
  }
}
