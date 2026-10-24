package kr.mujuthaek.api.area.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import kr.mujuthaek.api.area.domain.AddressMatch;
import kr.mujuthaek.api.area.domain.AddressMatch.Method;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

/**
 * 도로명주소 사전(area.address_points)에서 시군구·도로명·지하 여부·건물 번호로 주소를 찾아요 (3단계 1번째 길).
 *
 * <ol>
 *   <li>정확히: 도로명의 띄어쓰기를 지우고 그대로 (address_points_name_idx)
 *   <li>없으면 오타: 같은 시군구 도로명 목록(area.road_names)에서 trigram 유사도가 높은 순 {@value #FUZZY_CANDIDATES}개를 골라
 *       번호까지 같은 주소를 찾아요. 후보 하나에서만 맞을 때만 받아요
 * </ol>
 *
 * <p>같은 조건에 주소가 둘 이상이면(어느 것인지 모름) 고르지 않아요. 틀린 좌표보다 못 찾음이 나아요. JDBC만 써서 테스트에서는 {@code new}로 만들어요.
 */
@Component
public final class PostgresAddressPointLookup {

  static final int FUZZY_CANDIDATES = 3;

  private static final String EXACT =
      """
      select address_id, bjd_code, road_name, underground, bldg_main, bldg_sub,
             st_y(geom::geometry) as lat, st_x(geom::geometry) as lng
      from area.address_points
      where sigungu_code = ? and road_name = ? and underground = ? and bldg_main = ? and bldg_sub = ?
      limit 2
      """;

  // % 는 pg_trgm 유사도 기준(기본 0.3)을 넘는 것만이라 road_names_trgm_gix를 써요
  private static final String SIMILAR =
      """
      select road_name from area.road_names
      where sigungu_code = ? and road_name % ? and road_name <> ?
      order by similarity(road_name, ?) desc, road_name
      limit ?
      """;

  private final DataSource dataSource;

  public PostgresAddressPointLookup(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * @param sigunguCode 시군구 코드 5자리
   * @param roadName 정리한 주소의 도로명 (띄어쓰기는 여기서 지워요)
   * @param underground 0 지상, 1 지하
   * @param bldgSub 부번 (없으면 0)
   */
  public Optional<AddressMatch> find(
      String sigunguCode, String roadName, int underground, int bldgMain, int bldgSub) {
    String road = roadName == null ? "" : roadName.replaceAll("\\s+", "");
    if (sigunguCode == null || road.isEmpty()) {
      return Optional.empty();
    }
    try (Connection conn = dataSource.getConnection()) {
      List<AddressMatch> exact =
          exact(conn, sigunguCode, road, underground, bldgMain, bldgSub, Method.FILE);
      if (!exact.isEmpty()) {
        return exact.size() == 1 ? Optional.of(exact.getFirst()) : Optional.empty();
      }
      List<AddressMatch> fuzzy = new ArrayList<>();
      for (String candidate : similarRoads(conn, sigunguCode, road)) {
        fuzzy.addAll(
            exact(conn, sigunguCode, candidate, underground, bldgMain, bldgSub, Method.FUZZY));
      }
      return fuzzy.size() == 1 ? Optional.of(fuzzy.getFirst()) : Optional.empty();
    } catch (SQLException e) {
      throw new DataAccessResourceFailureException("주소 사전을 찾지 못했어요: " + e.getMessage(), e);
    }
  }

  private static List<AddressMatch> exact(
      Connection conn,
      String sigunguCode,
      String road,
      int underground,
      int bldgMain,
      int bldgSub,
      Method method)
      throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(EXACT)) {
      ps.setString(1, sigunguCode);
      ps.setString(2, road);
      ps.setInt(3, underground);
      ps.setInt(4, bldgMain);
      ps.setInt(5, bldgSub);
      List<AddressMatch> found = new ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          found.add(
              new AddressMatch(
                  rs.getString("address_id"),
                  rs.getString("bjd_code"),
                  rs.getString("road_name"),
                  rs.getInt("underground"),
                  rs.getInt("bldg_main"),
                  rs.getInt("bldg_sub"),
                  rs.getDouble("lat"),
                  rs.getDouble("lng"),
                  method));
        }
      }
      return found;
    }
  }

  private static List<String> similarRoads(Connection conn, String sigunguCode, String road)
      throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(SIMILAR)) {
      ps.setString(1, sigunguCode);
      ps.setString(2, road);
      ps.setString(3, road);
      ps.setString(4, road);
      ps.setInt(5, FUZZY_CANDIDATES);
      List<String> names = new ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          names.add(rs.getString(1));
        }
      }
      return names;
    }
  }
}
