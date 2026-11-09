package dev.scheduledesk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ScheduleService {
    private final JdbcClient db;
    public ScheduleService(JdbcClient db) { this.db = db; }

    public record Scanner(String id, String modality, String label) {}
    public record Appointment(UUID id, String scannerId, String demoRef, OffsetDateTime startsAt,
                              OffsetDateTime endsAt, String status, int version) {}
    public record Event(String action, int version, OffsetDateTime at) {}
    public record Booking(Appointment appointment, boolean replay) {}
    private static final String FIELDS = "id,scanner_id,demo_ref,starts_at,ends_at,status,version";
    private static final String SELECT = "SELECT " + FIELDS + " FROM appointments";

    private static final org.springframework.jdbc.core.RowMapper<Appointment> MAPPER =
        (r,i) -> new Appointment(r.getObject("id",UUID.class), r.getString("scanner_id"),
            r.getString("demo_ref"), r.getObject("starts_at",OffsetDateTime.class),
            r.getObject("ends_at",OffsetDateTime.class), r.getString("status"), r.getInt("version"));

    public List<Scanner> scanners() {
        return db.sql("SELECT id,modality,label FROM scanners ORDER BY id")
            .query((r,i)->new Scanner(r.getString(1),r.getString(2),r.getString(3))).list();
    }
    public List<Appointment> day(LocalDate date) {
        var start=date.atStartOfDay().atOffset(ZoneOffset.UTC);
        return db.sql(SELECT + " WHERE starts_at < ? AND ends_at > ? ORDER BY starts_at,scanner_id,id LIMIT 500")
            .params(start.plusDays(1),start).query(MAPPER).list();
    }
    public List<Event> history(UUID id) {
        return db.sql("SELECT action,appointment_version,at FROM schedule_events WHERE appointment_id=? ORDER BY seq")
            .param(id).query((r,i)->new Event(r.getString(1),r.getInt(2),r.getObject(3,OffsetDateTime.class))).list();
    }

    private static String hash(String scanner, String ref, OffsetDateTime start, int duration) {
        try {
            var b=String.join("|",scanner,ref,start.toInstant().toString(),Integer.toString(duration));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private void lockScanner(String scanner) {
        if(db.sql("SELECT id FROM scanners WHERE id=? FOR UPDATE").param(scanner).query(String.class).optional().isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown scanner");
    }
    private void ensureFree(String scanner,OffsetDateTime start,int minutes,UUID excluded) {
        String sql = "SELECT 1 FROM appointments WHERE scanner_id=? AND status='BOOKED' AND starts_at < ? AND ends_at > ?";
        var q=db.sql(sql+(excluded==null?"":" AND id<>?")+" LIMIT 1")
            .params(scanner,start.plusMinutes(minutes),start);
        if(excluded!=null) q.param(excluded);
        if(q.query(Integer.class).optional().isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"That scanner already has an appointment in this time window");
    }
    private void event(Appointment a,String action) {
        db.sql("INSERT INTO schedule_events(appointment_id,action,appointment_version) VALUES (?,?,?)")
            .params(a.id(),action,a.version()).update();
    }
    private static void validateSlot(OffsetDateTime start,int minutes) {
        if(start.getSecond()!=0 || start.getNano()!=0 || start.getMinute()%15!=0 || minutes<15 || minutes>120 || minutes%15!=0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use a quarter-hour start and a 15–120 minute duration in 15-minute increments");
    }
    @Transactional
    public Booking book(UUID requestKey,String scanner,String ref,OffsetDateTime start,int minutes) {
        validateSlot(start,minutes);
        String digest=hash(scanner,ref,start,minutes);
        // Serialize identical keys before the insert so a replay returns the existing appointment.
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended(?,0))").param(requestKey.toString())
            .query((r,i)->true).single();
        var old=db.sql(SELECT+" WHERE request_key=?").param(requestKey).query(MAPPER).optional();
        if(old.isPresent()) {
            var saved=db.sql("SELECT request_hash FROM appointments WHERE request_key=?").param(requestKey).query(String.class).single();
            if(!digest.equals(saved)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Request key already used for a different booking");
            return new Booking(old.get(),true);
        }
        lockScanner(scanner);
        ensureFree(scanner,start,minutes,null);
        var a=db.sql("INSERT INTO appointments(id,request_key,request_hash,scanner_id,demo_ref,starts_at,ends_at) VALUES (?,?,?,?,?,?,?) RETURNING "+FIELDS)
            .params(UUID.randomUUID(),requestKey,digest,scanner,ref,start,start.plusMinutes(minutes)).query(MAPPER).single();
        event(a,"BOOKED");
        return new Booking(a,false);
    }
    @Transactional
    public Appointment move(UUID id,int version,String scanner,OffsetDateTime start,int minutes) {
        validateSlot(start,minutes);
        lockScanner(scanner);
        ensureFree(scanner,start,minutes,id);
        var result=db.sql("UPDATE appointments SET scanner_id=?,starts_at=?,ends_at=?,version=version+1 WHERE id=? AND version=? AND status='BOOKED' RETURNING "+FIELDS)
            .params(scanner,start,start.plusMinutes(minutes),id,version).query(MAPPER).optional();
        var a=result.orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"Appointment changed; reload the schedule"));
        event(a,"MOVED");
        return a;
    }
    @Transactional
    public Appointment cancel(UUID id,int version) {
        var result=db.sql("UPDATE appointments SET status='CANCELLED',version=version+1 WHERE id=? AND version=? AND status='BOOKED' RETURNING "+FIELDS)
            .params(id,version).query(MAPPER).optional();
        var a=result.orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"Appointment changed; reload the schedule"));
        event(a,"CANCELLED");
        return a;
    }
}
