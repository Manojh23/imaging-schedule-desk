package dev.scheduledesk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named="SCHEDULE_TEST_DB",matches="1")
class ScheduleIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcClient db;
    private static final String DATE="2030-06-05";
    @BeforeEach void reset() {
        http.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        assertThat(db.sql("SELECT current_database()").query(String.class).single()).isEqualTo("imaging_test");
        db.sql("DELETE FROM schedule_events").update();
        db.sql("DELETE FROM appointments").update();
    }
    private static Map<String,Object> slot(String scanner,String clock,int minutes) {
        return Map.of("scannerId",scanner,"startsAt",DATE+"T"+clock+":00Z","durationMinutes",minutes);
    }
    private static Map<String,Object> booking(UUID key,String scanner,String clock,int minutes,String ref) {
        return Map.of("requestKey",key,"demoRef",ref,"slot",slot(scanner,clock,minutes));
    }
    private ResponseEntity<JsonNode> book(Map<String,Object> body) {
        return http.postForEntity("/api/appointments",body,JsonNode.class);
    }
    private ResponseEntity<JsonNode> move(String id,int version,String scanner,String time) {
        return http.exchange("/api/appointments/"+id,HttpMethod.PATCH,
            new HttpEntity<>(Map.of("version",version,"slot",slot(scanner,time,30))),JsonNode.class);
    }
    private ResponseEntity<JsonNode> cancel(String id,int version) {
        return http.postForEntity("/api/appointments/"+id+"/cancel",Map.of("version",version),JsonNode.class);
    }
    private String make(String scanner,String clock,int minutes) {
        var r=book(booking(UUID.randomUUID(),scanner,clock,minutes,"DEMO-TEST"));
        assertThat(r.getStatusCode().value()).isEqualTo(201);
        return r.getBody().get("id").asText();
    }
    @Test void bookingAppearsInDailyWorklistAndAudit() {
        String id=make("MRI-01","09:00",30);
        var list=http.getForObject("/api/appointments?date="+DATE,JsonNode.class);
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).get("id").asText()).isEqualTo(id);
        var h=http.getForObject("/api/appointments/"+id+"/events",JsonNode.class);
        assertThat(h.get(0).get("action").asText()).isEqualTo("BOOKED");
    }
    @Test void replayDoesNotDuplicateAndChangedReplayConflicts() {
        UUID k=UUID.randomUUID();
        var body=booking(k,"MRI-01","09:00",30,"DEMO-ONE");
        var first=book(body);var replay=book(body);
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(book(booking(k,"MRI-01","09:30",30,"DEMO-ONE")).getStatusCode().value()).isEqualTo(409);
    }
    @Test void adjacentAppointmentsAndDifferentScannersAreAllowed() {
        make("MRI-01","09:00",30);
        assertThat(book(booking(UUID.randomUUID(),"MRI-01","09:15",30,"DEMO-OVERLAP")).getStatusCode().value()).isEqualTo(409);
        make("MRI-01","09:30",30);
        make("CT-01","09:00",30);
    }
    @Test void simultaneousOverlappingBookingsHaveOnlyOneWinner() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        var barrier=new CyclicBarrier(2);
        try {
            Callable<Integer> a=()->{barrier.await(10,TimeUnit.SECONDS);return book(booking(UUID.randomUUID(),"MRI-01","10:00",60,"DEMO-A")).getStatusCode().value();};
            Callable<Integer> b=()->{barrier.await(10,TimeUnit.SECONDS);return book(booking(UUID.randomUUID(),"MRI-01","10:15",30,"DEMO-B")).getStatusCode().value();};
            var f=pool.submit(a);var g=pool.submit(b);
            assertThat(java.util.List.of(f.get(20,TimeUnit.SECONDS),g.get(20,TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(201,409);
        } finally {pool.shutdownNow();}
    }
    @Test void moveRejectsOccupiedSlotAndStaleVersion() {
        String id=make("MRI-01","09:00",30);
        make("CT-01","09:15",30);
        assertThat(move(id,0,"CT-01","09:15").getStatusCode().value()).isEqualTo(409);
        var moved=move(id,0,"MRI-01","09:15");
        assertThat(moved.getStatusCode().value()).isEqualTo(200);
        assertThat(moved.getBody().get("version").asInt()).isEqualTo(1);
        assertThat(move(id,0,"MRI-01","10:30").getStatusCode().value()).isEqualTo(409);
    }
    @Test void cancellationFreesSlotAndPreservesHistory() {
        String id=make("MRI-01","09:00",30);
        assertThat(cancel(id,0).getStatusCode().value()).isEqualTo(200);
        make("MRI-01","09:00",30);
        var h=http.getForObject("/api/appointments/"+id+"/events",JsonNode.class);
        assertThat(h.size()).isEqualTo(2);
        assertThat(h.get(1).get("action").asText()).isEqualTo("CANCELLED");
    }
    @Test void invalidReferenceAndOffGridTimeAreRejected() {
        assertThat(book(booking(UUID.randomUUID(),"MRI-01","09:00",30,"WRONG")).getStatusCode().value()).isEqualTo(400);
        assertThat(book(booking(UUID.randomUUID(),"MRI-01","09:02",30,"DEMO-A")).getStatusCode().value()).isEqualTo(400);
    }
    @Test void readinessChecksDatabase() {
        var r=http.getForEntity("/actuator/health/readiness",JsonNode.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody().get("status").asText()).isEqualTo("UP");
    }
}
