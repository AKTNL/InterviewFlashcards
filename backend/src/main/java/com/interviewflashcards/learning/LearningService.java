package com.interviewflashcards.learning;

import com.fasterxml.jackson.databind.*;
import com.interviewflashcards.common.time.LearningTime;
import com.interviewflashcards.common.web.*;
import com.interviewflashcards.sync.MutationService;
import com.interviewflashcards.learning.ReviewRules.*;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class LearningService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MutationService mutations;
    private final LearningTime time;
    private final ReviewConfiguration rules;
    public LearningService(JdbcTemplate jdbc,ObjectMapper mapper,MutationService mutations,LearningTime time,ReviewConfiguration rules) {
        this.jdbc=jdbc; this.mapper=mapper; this.mutations=mutations; this.time=time; this.rules=rules;
    }
    public JsonNode start(UUID user,LearningController.Start command) throws IOException {
        return mutations.execute(user,new MutationService.Command(command.operationId(),command.dataEpoch()),"learning.start",command,()->{
            if(command.cardIds()==null || command.cardIds().isEmpty() || command.cardIds().size()>100 || new HashSet<>(command.cardIds()).size()!=command.cardIds().size()) invalid();
            UUID id=UUID.randomUUID();
            jdbc.update("insert into learning_session(id,user_id,learning_date) values(?,?,?)",id,user,time.today());
            int order=0;
            for(UUID card:command.cardIds()) {
                long version=cardVersion(user,card);
                jdbc.update("insert into session_item(id,user_id,session_id,card_id,sort_order,expected_review_version) values(?,?,?,?,?,?)",UUID.randomUUID(),user,id,card,order++,version);
            }
            return new MutationService.Change(obj("session",session(user,id)),0,true);
        });
    }
    private long cardVersion(UUID user,UUID card) {
        var rows=jdbc.queryForList("select review_version from card where user_id=? and id=?",user,card);
        if(rows.isEmpty()) throw new ApiFailure(ErrorCode.NOT_FOUND);
        return ((Number)rows.getFirst().get("review_version")).longValue();
    }
    private Map<String,Object> sessionRow(UUID user,UUID id) {
        var rows=jdbc.queryForList("select * from learning_session where user_id=? and id=?",user,id);
        if(rows.isEmpty()) throw new ApiFailure(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }
    private List<Map<String,Object>> queue(UUID user,UUID id) {
        return jdbc.queryForList("""
            select i.id,i.card_id as "cardId",i.sort_order as "sortOrder",i.completed,i.is_replay as "isReplay",
            i.skipped,i.expected_review_version as "expectedVersion",c.question,c.review_version as "currentVersion"
            from session_item i join card c on c.user_id=i.user_id and c.id=i.card_id
            where i.user_id=? and i.session_id=? order by i.sort_order
            """,user,id);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> session(UUID user,UUID id) {
        var row=sessionRow(user,id); var items=queue(user,id);
        var current=items.stream().filter(i->!Boolean.TRUE.equals(i.get("completed"))).findFirst().orElse(null);
        var events=jdbc.queryForList("""
            select e.id,e.card_id as "cardId",e.kind,e.rating,e.after_state as "afterState",e.is_replay as "isReplay"
            from review_event e join session_item i on i.user_id=e.user_id and i.id=e.session_item_id
            where e.user_id=? and i.session_id=? and e.kind<>'UNDO'
              and not exists(select 1 from review_event u where u.user_id=e.user_id and u.undo_of=e.id)
            order by e.event_order desc
            """,user,id);
        events.forEach(e->e.put("afterState",json(e.get("afterState"))));
        State state=current==null?null:state(user,(UUID)current.get("cardId"));
        var latest=jdbc.queryForList("""
            select e.id,i.session_id,e.session_version_after,c.review_version,e.after_state->>'version' as scored_version
            from review_event e join session_item i on i.user_id=e.user_id and i.id=e.session_item_id
            join card c on c.user_id=e.user_id and c.id=e.card_id
            where e.user_id=? and e.kind<>'UNDO'
            and not exists(select 1 from review_event u where u.user_id=e.user_id and u.undo_of=e.id)
            order by e.event_order desc limit 1
            """,user);
        boolean canUndo=!latest.isEmpty() && id.equals(latest.getFirst().get("session_id"))
            && Objects.equals(row.get("version"),latest.getFirst().get("session_version_after"))
            && latest.getFirst().get("review_version").toString().equals(latest.getFirst().get("scored_version"));
        return obj("id",id,"version",row.get("version"),"status",row.get("status"),"learningDate",row.get("learning_date").toString(),
            "today",time.today(),"expired",!row.get("learning_date").toString().equals(time.today().toString()),
            "items",items,"current",current,"review",state,"events",events,"canUndo",canUndo,
            "sameDayReplay",state!=null && LocalDate.ofInstant(state.lastReviewedAt(),time.learningZone()).equals(time.today()));
    }
    private State state(UUID user,UUID card) {
        var rows=jdbc.queryForList("select * from review_state where user_id=? and card_id=?",user,card);
        if(rows.isEmpty()) return null;
        var s=rows.getFirst();
        return new State(((Timestamp)s.get("first_learned_at")).toInstant(),((Timestamp)s.get("last_reviewed_at")).toInstant(),
            ((java.sql.Date)s.get("due_date")).toLocalDate(),((Number)s.get("stage")).intValue(),Rating.valueOf((String)s.get("latest_rating")),
            ((Number)s.get("lapse_count")).intValue(),((Number)s.get("version")).longValue());
    }
    private void saveState(UUID user,UUID card,State state,long version) {
        jdbc.update("update card set review_version=? where user_id=? and id=?",version,user,card);
        if(state==null) { jdbc.update("delete from review_state where user_id=? and card_id=?",user,card); return; }
        jdbc.update("""
            insert into review_state(id,user_id,card_id,first_learned_at,last_reviewed_at,due_date,stage,latest_rating,lapse_count,version)
            values(?,?,?,?,?,?,?,?,?,?) on conflict(user_id,card_id) do update set
            first_learned_at=excluded.first_learned_at,last_reviewed_at=excluded.last_reviewed_at,due_date=excluded.due_date,
            stage=excluded.stage,latest_rating=excluded.latest_rating,lapse_count=excluded.lapse_count,version=excluded.version
            """,UUID.randomUUID(),user,card,Timestamp.from(state.firstLearnedAt()),Timestamp.from(state.lastReviewedAt()),state.dueDate(),
            state.stage(),state.latestRating().name(),state.lapseCount(),version);
    }
    public JsonNode write(UUID user,UUID id,LearningController.Write command) throws IOException {
        return mutations.execute(user,new MutationService.Command(command.operationId(),command.dataEpoch()),"learning.write",
            obj("id",id,"command",command),()->{
                var session=sessionRow(user,id);
                long sv=((Number)session.get("version")).longValue();
                if(command.expectedVersion()==null || command.expectedVersion()!=sv) conflict(user,id);
                if(!session.get("learning_date").toString().equals(time.today().toString()) || "INVALIDATED".equals(session.get("status")))
                    throw new ApiFailure(ErrorCode.SESSION_EXPIRED,Map.of("current",session(user,id)));
                var p=command.payload();
                if("UNDO".equals(command.action())) return undo(user,id,command,sv);
                var items=queue(user,id);
                var current=items.stream().filter(i->!Boolean.TRUE.equals(i.get("completed"))).findFirst().orElseThrow(()->new ApiFailure(ErrorCode.SESSION_EXPIRED));
                UUID item=(UUID)current.get("id"),card=(UUID)current.get("cardId");
                if(!item.toString().equals(p.path("itemId").asText())) conflict(user,id);
                long version=cardVersion(user,card);
                if(!p.path("expectedReviewVersion").isIntegralNumber() || !p.path("expectedReviewVersion").canConvertToLong() || p.path("expectedReviewVersion").asLong()!=version
                        || ((Number)current.get("expectedVersion")).longValue()!=version) conflict(user,id);
                State before=state(user,card);
                if("SKIP".equals(command.action())) {
                    if(!Boolean.TRUE.equals(current.get("isReplay"))) invalid();
                    jdbc.update("update session_item set completed=true,skipped=true where user_id=? and id=?",user,item);
                    advance(user,id,sv+1);
                    return new MutationService.Change(obj("session",session(user,id)),sv+1,true);
                }
                if(!"RATE".equals(command.action()) && !"RELEARN".equals(command.action())) invalid();
                if(!confirmed(p,"answerRevealed") && "RATE".equals(command.action())) invalid();
                // PostgreSQL timestamps retain microseconds; event snapshots must use that same precision.
                Instant now=time.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                State after; boolean replay;
                if("RELEARN".equals(command.action())) {
                    if(before==null || !confirmed(p,"confirmed")) invalid();
                    after=new State(before.firstLearnedAt(),before.lastReviewedAt(),time.today(),-1,before.latestRating(),before.lapseCount(),version+1);
                    replay=false;
                } else {
                    Rating rating;
                    try { rating=Rating.valueOf(p.path("rating").asText()); } catch(IllegalArgumentException e) { throw new ApiFailure(ErrorCode.INVALID_REQUEST); }
                    var result=ReviewRules.rate(before,rating,now,time.learningZone(),rules.intervals(),version+1);
                    after=result.state(); replay=result.replay();
                    if(before!=null && before.dueDate().isAfter(time.today()) && !replay && !confirmed(p,"confirmEarly"))
                        throw new ApiFailure(ErrorCode.EARLY_REVIEW_CONFIRMATION,Map.of("dueDate",before.dueDate()));
                }
                UUID event=UUID.randomUUID();
                insertEvent(user,command,event,card,item,before,after,replay,null,items,sv+1,command.action());
                saveState(user,card,after,version+1);
                if("RATE".equals(command.action())) {
                    jdbc.update("update session_item set completed=true where user_id=? and id=?",user,item);
                    if(after.latestRating()==Rating.FORGOT && !Boolean.TRUE.equals(current.get("isReplay"))) {
                        int reserved=jdbc.update("""
                            insert into review_replay_allowance(user_id,card_id,learning_date,session_id,event_id) values(?,?,?,?,?)
                            on conflict do nothing
                            """,user,card,time.today(),id,event);
                        if(reserved==1) {
                            int order=items.stream().mapToInt(i->((Number)i.get("sortOrder")).intValue()).max().orElse(0)+1;
                            jdbc.update("""
                                insert into session_item(id,user_id,session_id,card_id,sort_order,expected_review_version,is_replay,replay_count)
                                values(?,?,?,?,?,?,true,1)
                                """,UUID.randomUUID(),user,id,card,order,version+1);
                        }
                    }
                } else jdbc.update("update session_item set expected_review_version=? where user_id=? and id=?",version+1,user,item);
                advance(user,id,sv+1);
                return new MutationService.Change(obj("eventId",event,"dueDate",after.dueDate(),"isReplay",replay,"session",session(user,id)),sv+1,true);
            });
    }
    private MutationService.Change undo(UUID user,UUID id,LearningController.Write command,long sv) throws IOException {
        var rows=jdbc.queryForList("""
            select e.*,i.session_id from review_event e join session_item i on i.user_id=e.user_id and i.id=e.session_item_id
            where e.user_id=? and e.kind<>'UNDO'
            and not exists(select 1 from review_event u where u.user_id=e.user_id and u.undo_of=e.id)
            order by e.event_order desc limit 1
            """,user);
        if(rows.isEmpty()) conflict(user,id);
        var event=rows.getFirst(); UUID card=(UUID)event.get("card_id");
        if(!id.equals(event.get("session_id")) || !event.get("id").toString().equals(command.payload().path("eventId").asText())
                || sv!=((Number)event.get("session_version_after")).longValue()) conflict(user,id);
        State after=mapper.treeToValue(json(event.get("after_state")),State.class),before=mapper.treeToValue(json(event.get("before_state")),State.class);
        long version=cardVersion(user,card);
        if(after.version()!=version) conflict(user,id);
        var oldQueue=json(event.get("before_queue"));
        UUID compensation=UUID.randomUUID();
        insertEvent(user,command,compensation,card,(UUID)event.get("session_item_id"),after,before,true,(UUID)event.get("id"),queue(user,id),sv+1,"UNDO");
        jdbc.update("delete from review_replay_allowance where user_id=? and event_id=?",user,event.get("id"));
        var keep=new HashSet<UUID>(); oldQueue.forEach(i->keep.add(UUID.fromString(i.get("id").asText())));
        for(var item:queue(user,id)) if(!keep.contains(item.get("id")))
            jdbc.update("delete from session_item where user_id=? and id=?",user,item.get("id"));
        for(var item:oldQueue) jdbc.update("update session_item set completed=?,skipped=?,expected_review_version=? where user_id=? and id=?",
            item.path("completed").asBoolean(),item.path("skipped").asBoolean(),
            item.path("cardId").asText().equals(card.toString())?version+1:item.path("expectedVersion").asLong(),user,UUID.fromString(item.get("id").asText()));
        saveState(user,card,before,version+1);
        advance(user,id,sv+1);
        return new MutationService.Change(obj("eventId",compensation,"session",session(user,id)),sv+1,true);
    }
    private void insertEvent(UUID user,LearningController.Write c,UUID event,UUID card,UUID item,State before,State after,boolean replay,
                             UUID undo,List<Map<String,Object>> queue,long sv,String kind) throws IOException {
        jdbc.update("""
            insert into review_event(id,user_id,data_epoch,operation_id,card_id,session_item_id,rating,before_state,after_state,
            algorithm_version,learning_date,is_replay,undo_of,before_queue,session_version_after,kind)
            values(?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?::jsonb,?,?)
            """,event,user,c.dataEpoch(),c.operationId(),card,item,(after!=null?after:before).latestRating().name(),
            mapper.writeValueAsString(before),mapper.writeValueAsString(after),rules.version(),time.today(),replay,undo,mapper.writeValueAsString(queue),sv,kind);
    }
    private void advance(UUID user,UUID id,long version) {
        jdbc.update("""
            update learning_session set version=?,status=case when exists(select 1 from session_item where user_id=? and session_id=? and not completed)
            then 'ACTIVE' else 'COMPLETED' end where user_id=? and id=?
            """,version,user,id,user,id);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> summary(UUID user) {
        // Effective history, never increment-only counters; compensation rows are excluded.
        var counts=jdbc.queryForMap("""
            with effective as (
                select e.* from review_event e where e.user_id=? and e.kind='RATE'
                and not exists(select 1 from review_event u where u.user_id=e.user_id and u.undo_of=e.id)
            ), firsts as (select card_id,min(learning_date) as first_day from effective group by card_id)
            select (select count(*) from review_state where user_id=?) as learned,
            (select count(*) from firsts where first_day=?) as "todayNew",
            (select count(distinct e.card_id) from effective e join firsts f on f.card_id=e.card_id where e.learning_date=? and f.first_day<?) as "todayReview"
            """,user,user,time.today(),time.today(),time.today());
        return obj("learningDate",time.today(),"learned",counts.get("learned"),"todayNew",counts.get("todayNew"),"todayReview",counts.get("todayReview"));
    }
    private void conflict(UUID user,UUID id) { throw new ApiFailure(ErrorCode.VERSION_CONFLICT,Map.of("current",session(user,id))); }
    private static boolean confirmed(JsonNode p,String field) { return p.path(field).isBoolean() && p.path(field).asBoolean(); }
    private static void invalid() { throw new ApiFailure(ErrorCode.INVALID_REQUEST); }
    private JsonNode json(Object v) { try { return mapper.readTree(v.toString()); } catch(IOException e) { throw new IllegalStateException(e); } }
    private static Map<String,Object> obj(Object... fields) { var m=new LinkedHashMap<String,Object>();for(int i=0;i<fields.length;i+=2)m.put((String)fields[i],fields[i+1]);return m; }
}
