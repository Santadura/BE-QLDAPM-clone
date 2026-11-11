package vn.iig.academic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Typed Academic PostgreSQL is the source for timetable GET; Core still
 * validates every live bearer, class permission and source watermark. */
@Service
public class AcademicEventSessionReadService {
    private final AcademicCoreClient core;
    private final AcademicSessionSourceClient source;
    private final AcademicSessionSourceRepository local;
    private final AcademicSessionSourceRelay relay;
    private final ObjectMapper json;

    public AcademicEventSessionReadService(AcademicCoreClient core,
            AcademicSessionSourceClient source,AcademicSessionSourceRepository local,
            AcademicSessionSourceRelay relay,ObjectMapper json) {
        this.core=core;this.source=source;this.local=local;
        this.relay=relay;this.json=json;
    }

    public AcademicReadModelService.Result sessions(String classId,String bearer) {
        if(classId==null || !classId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,49}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid class ID");
        core.currentPrincipal(bearer);
        for(int i=0;i<3;i++) {
            // Core's private version endpoint revalidates the exact class ACL.
            long firstRevision=core.sessionRevision(classId,bearer).revision();
            var first=source.summary();
            if(behind(first)) {
                // Core's ACL is verified FIRST. Import only a finite number
                // of source events synchronously, never call its JSON snapshot.
                // The relay commits each event in Academic PG BEFORE Core ack.
                relay.drain(3);
                relay.backfill(3);
                first=source.summary();
            }
            if(behind(first))
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Academic timetable is not fully reconciled with Core");
            final JsonNode payload;
            try {
                payload=json.readTree(local.eventBackedClassSessions(classId));
            } catch(ResponseStatusException ex) {throw ex;}
              catch(Exception ex) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Invalid Academic event-backed timetable JSON",ex);
            }
            if(payload==null || !payload.isArray())
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Academic local timetable must be an array");
            long lastRevision=core.sessionRevision(classId,bearer).revision();
            var after=source.summary();
            if(firstRevision==lastRevision && first.equals(after) &&
                    local.historyCursor()==after.maxEventId())
                return new AcademicReadModelService.Result(payload,Instant.now());
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Academic timetable changed during live source validation");
    }    private boolean behind(AcademicSessionSourceClient.Summary state) {
        return state==null || state.eventCount()<0 || state.pendingEvents()!=0 ||
            state.maxEventId()<0 || state.sourceRows()<0 ||
            (state.eventCount()==0 && state.maxEventId()!=0) ||
            local.historyCursor()!=state.maxEventId() ||
            local.receiptCount()!=state.eventCount();
    }
}

