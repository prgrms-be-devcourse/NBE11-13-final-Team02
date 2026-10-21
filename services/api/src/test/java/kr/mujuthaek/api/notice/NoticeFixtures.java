package kr.mujuthaek.api.notice;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * notice 테스트용 extraction.completed payload. 계약 예시(contracts/examples/extraction.completed.json)를 바꿔
 * 써요.
 */
public final class NoticeFixtures {

  public static final JsonMapper JSON = JsonMapper.builder().build();

  private NoticeFixtures() {}

  /** 새 원본(rawPostId)의 모집공고 payload. */
  public static ObjectNode payload(UUID rawPostId, int rawVersion) {
    ObjectNode p = (ObjectNode) example().get("payload");
    p.put("rawPostId", rawPostId.toString());
    p.put("rawVersion", rawVersion);
    ObjectNode ref = (ObjectNode) p.get("notice").get("sourceReferences").get(0);
    ref.put("rawPostId", rawPostId.toString());
    ref.put("rawVersion", rawVersion);
    return p;
  }

  /** 정정공고 payload: 다른 원본이 original을 가리켜요. */
  public static ObjectNode correction(UUID rawPostId, UUID original) {
    ObjectNode p = payload(rawPostId, 1);
    ObjectNode ref = (ObjectNode) p.get("notice").get("sourceReferences").get(0);
    ref.put("kind", "CORRECTION");
    ref.put("supersedesPostId", original.toString());
    return p;
  }

  public static ObjectNode followUp(UUID rawPostId, UUID original) {
    ObjectNode p = correction(rawPostId, original);
    ((ObjectNode) p.get("notice").get("sourceReferences").get(0)).put("kind", "FOLLOW_UP");
    return p;
  }

  private static ObjectNode example() {
    try {
      Path root = Path.of("").toAbsolutePath();
      while (!Files.isDirectory(root.resolve("contracts"))) {
        root = root.getParent();
      }
      return (ObjectNode)
          JSON.readTree(
              Files.readString(root.resolve("contracts/examples/extraction.completed.json")));
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
