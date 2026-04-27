package dev.mdz.wolpi.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mdz.wolpi.model.ImageSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RequestType")
class RequestTypeTest {

    @Test
    @DisplayName("should classify requests with unknown crop geometry as other")
    void shouldClassifyRequestsWithUnknownCropGeometryAsOther() {
        RequestType requestType = RequestType.classify("10,20,30,40", SizeBucket.SMALL, null, new ImageSize(1000, 800));

        assertThat(requestType).isEqualTo(RequestType.OTHER);
    }

    @Test
    @DisplayName("should classify full requests with unknown output size as other")
    void shouldClassifyFullRequestsWithUnknownOutputSizeAsOther() {
        RequestType requestType = RequestType.classify("full", null, null, new ImageSize(1000, 800));

        assertThat(requestType).isEqualTo(RequestType.OTHER);
    }
}
