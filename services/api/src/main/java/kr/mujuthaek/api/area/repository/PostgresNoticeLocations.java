package kr.mujuthaek.api.area.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import kr.mujuthaek.api.area.domain.Coordinate;
import kr.mujuthaek.api.area.domain.GeocodeResult;
import kr.mujuthaek.api.area.domain.GeocodeResult.FailReason;
import kr.mujuthaek.api.area.domain.GeocodeResult.Method;
import kr.mujuthaek.api.area.domain.GeocodeResult.Status;
import kr.mujuthaek.api.area.domain.NoticeLocation;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

/**
 * 공고 위치의 좌표(area.notice_locations) 읽기·쓰기. 같은 공고·위치 ID는 한 줄이고, 다시 저장하면 area 위치 ID(location_id)는 그대로
 * 두고 내용만 바꿔요(세권 지표·점수가 그 ID를 가리켜요). JDBC만 써서 테스트에서는 {@code new}로 만들어요.
 */
@Component
public final class PostgresNoticeLocations {

  private static final String REUSABLE =
      """
      select method, st_y(geom::geometry) as lat, st_x(geom::geometry) as lng, bjd_code_found, region_mismatch
      from area.notice_locations
      where address_key = ? and status = 'OK'
      order by calculated_at desc
      limit 1
      """;

  private static final String SAVE =
      """
      insert into area.notice_locations as t (notice_id, notice_location_id, notice_version, kind, address_text,
                                              address_key, bjd_code_given, bjd_code_found, geom, status, method,
                                              fail_reason, region_mismatch, calculated_at)
      values (?, ?, ?, ?, ?, ?, ?, ?,
              case when ?::float8 is null then null else st_setsrid(st_makepoint(?, ?), 4326)::geography end,
              ?, ?, ?, ?, now())
      on conflict (notice_id, notice_location_id) do update
        set notice_version = excluded.notice_version, kind = excluded.kind, address_text = excluded.address_text,
            address_key = excluded.address_key, bjd_code_given = excluded.bjd_code_given,
            bjd_code_found = excluded.bjd_code_found, geom = excluded.geom, status = excluded.status,
            method = excluded.method, fail_reason = excluded.fail_reason,
            region_mismatch = excluded.region_mismatch, calculated_at = now()
      returning location_id
      """;

  private final DataSource dataSource;

  public PostgresNoticeLocations(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * 같은 주소 키로 이미 찾은 정확한 좌표(OK). 대략 위치(APPROX)·실패는 나중에 더 잘 찾을 수 있어서 다시 써요(재사용 안 함). 시군구·주소 ID는 저장하지
   * 않아서 비어요.
   */
  public Optional<GeocodeResult> reusable(String addressKey) {
    if (addressKey == null || addressKey.isBlank()) {
      return Optional.empty();
    }
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(REUSABLE)) {
      ps.setString(1, addressKey);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new GeocodeResult(
                Status.OK,
                Method.valueOf(rs.getString("method")),
                new Coordinate(rs.getDouble("lat"), rs.getDouble("lng")),
                rs.getString("bjd_code_found"),
                null,
                null,
                null,
                rs.getBoolean("region_mismatch")));
      }
    } catch (SQLException e) {
      throw new DataAccessResourceFailureException("공고 위치를 읽지 못했어요: " + e.getMessage(), e);
    }
  }

  /** 저장한 줄의 area 위치 ID를 돌려줘요 (처음이면 새로, 있던 위치면 그대로). */
  public UUID save(NoticeLocation location, String addressKey, GeocodeResult result) {
    Coordinate c = result.coordinate();
    FailReason reason = result.failReason();
    try (Connection conn = dataSource.getConnection();
        PreparedStatement ps = conn.prepareStatement(SAVE)) {
      ps.setObject(1, location.noticeId());
      ps.setString(2, location.noticeLocationId());
      ps.setInt(3, location.noticeVersion());
      ps.setString(4, location.kind() == null ? "UNKNOWN" : location.kind());
      ps.setString(5, location.address() == null ? "" : location.address());
      ps.setString(6, addressKey);
      ps.setString(7, location.bjdCode());
      ps.setString(8, result.bjdCodeFound());
      ps.setObject(9, c == null ? null : c.lng(), Types.DOUBLE);
      ps.setObject(10, c == null ? null : c.lng(), Types.DOUBLE);
      ps.setObject(11, c == null ? null : c.lat(), Types.DOUBLE);
      ps.setString(12, result.status().name());
      ps.setString(13, result.method() == null ? null : result.method().name());
      ps.setString(14, reason == null ? null : reason.name());
      ps.setBoolean(15, result.regionMismatch());
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getObject(1, UUID.class);
      }
    } catch (SQLException e) {
      throw new DataAccessResourceFailureException("공고 위치를 저장하지 못했어요: " + e.getMessage(), e);
    }
  }
}
