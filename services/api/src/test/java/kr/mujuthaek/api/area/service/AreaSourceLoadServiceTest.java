package kr.mujuthaek.api.area.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import kr.mujuthaek.api.area.domain.AreaLoadReport;
import kr.mujuthaek.api.area.domain.AreaLoadRequest;
import kr.mujuthaek.api.area.domain.AreaSource;
import kr.mujuthaek.api.area.repository.PostgresAreaBaseDataLoader;
import kr.mujuthaek.api.area.repository.S3AreaFileStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AreaSourceLoadServiceTest {

  static final String KEY = "area/STORES/2026-06-30/stores_gangwon.csv";

  final S3AreaFileStore files = mock(S3AreaFileStore.class);
  final PostgresAreaBaseDataLoader loader = mock(PostgresAreaBaseDataLoader.class);
  final AreaSourceLoadService service = new AreaSourceLoadService(files, loader);

  @Test
  void opensTheS3FileAndPassesTheRequestToTheLoader() {
    var content = new ByteArrayInputStream(new byte[0]);
    when(files.open(KEY)).thenReturn(content);
    when(loader.load(any(), eq(content))).thenReturn(succeeded(3));

    AreaLoadReport report = service.load(command());

    assertThat(report.rowsInserted()).isEqualTo(3);
    var request = ArgumentCaptor.forClass(AreaLoadRequest.class);
    verify(loader).load(request.capture(), eq(content));
    assertThat(request.getValue().scope()).isEqualTo("강원");
    assertThat(request.getValue().fileKey()).isEqualTo(KEY);
  }

  @Test
  void returnsFailedReportsForFileProblems() {
    when(files.open(KEY)).thenReturn(new ByteArrayInputStream(new byte[0]));
    when(loader.load(any(), any())).thenReturn(AreaLoadReport.failed(UUID.randomUUID(), "칸 없음"));

    assertThat(service.load(command()).status()).isEqualTo(AreaLoadReport.Status.FAILED);
  }

  @Test
  void closingTheS3StreamFailsLoudly() {
    when(files.open(KEY))
        .thenReturn(
            new InputStream() {
              @Override
              public int read() {
                return -1;
              }

              @Override
              public void close() throws IOException {
                throw new IOException("연결 끊김");
              }
            });
    when(loader.load(any(), any())).thenReturn(succeeded(0));

    assertThatThrownBy(() -> service.load(command())).isInstanceOf(UncheckedIOException.class);
  }

  static AreaSourceLoadService.Command command() {
    return new AreaSourceLoadService.Command(
        AreaSource.STORES, "강원", KEY, "ab".repeat(32), LocalDate.of(2026, 6, 30), false);
  }

  static AreaLoadReport succeeded(int inserted) {
    return new AreaLoadReport(
        UUID.randomUUID(),
        AreaLoadReport.Status.SUCCEEDED,
        inserted,
        0,
        0,
        0,
        inserted,
        0,
        0,
        0,
        Map.of(),
        null);
  }
}
