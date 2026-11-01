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
    private final ReviewPolicyService policies;
    public LearningService(JdbcTemplate jdbc,ObjectMapper mapper,MutationService mutations,LearningTime time,ReviewPolicyService policies) {
        this.jdbc=jdbc; this.mapper=mapper; this.mutations=mutations; this.time=time; this.policies=policies;
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
        var items=jdbc.queryForList("""
            select i.id,i.card_id as "cardId",i.sort_order as "sortOrder",i.completed,i.is_replay as "isReplay",
            i.skipped,i.item_kind as "itemKind",i.expected_review_version as "expectedVersion",c.question,c.review_version as "currentVersion",
            c.chapter_id,ch.check_status,r.due_date,
            exists(select 1 from learning_preference_chapter pc where pc.user_id=i.user_id and pc.chapter_id=c.chapter_id) as selected
            from session_item i left join card c on c.user_id=i.user_id and c.id=i.card_id
            left join chapter ch on ch.user_id=c.user_id and ch.id=c.chapter_id
            left join review_state r on r.user_id=c.user_id and r.card_id=c.id
            where i.user_id=? and i.session_id=? order by i.sort_order
            """,user,id);
        boolean planned=!"MANUAL".equals(sessionRow(user,id).get("mode"));
        int remaining=planned?remainingNew(user):Integer.MAX_VALUE;
        for(var i:items) {
            String reason=null;
            if(!Boolean.TRUE.equals(i.get("completed")) && !Objects.equals(i.get("expectedVersion"),i.get("currentVersion"))) reason="已有其他学习记录";
            if(planned && !Boolean.TRUE.equals(i.get("completed")) && reason==null) {
                if(i.get("currentVersion")==null) reason="卡片已删除";
                else if(!Boolean.TRUE.equals(i.get("selected")) || !"IMPORTED".equals(i.get("check_status"))) reason="章节已暂停";
                else if(!Objects.equals(i.get("expectedVersion"),i.get("currentVersion"))) reason="已有其他学习记录";
                else if("NEW".equals(i.get("itemKind"))) {
                    if(i.get("due_date")!=null) reason="此题已学习";
                    else if(remaining<=0) reason="今日新题额度已用完";
                    else remaining--;
                } else if(!Boolean.TRUE.equals(i.get("isReplay")) && (i.get("due_date")==null || ((java.sql.Date)i.get("due_date")).toLocalDate().isAfter(time.today()))) reason="已不在到期集合";
            }
            i.put("unavailable",reason!=null); i.put("skipReason",reason);
            i.remove("chapter_id");i.remove("check_status");i.remove("due_date");i.remove("selected");
        }
        return items;
    }
    private boolean pending(Map<String,Object> i) { return !Boolean.TRUE.equals(i.get("completed")) && !Boolean.TRUE.equals(i.get("unavailable")); }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> session(UUID user,UUID id) {
        var row=sessionRow(user,id); var items=queue(user,id);
        var current="INVALIDATED".equals(row.get("status"))?null:items.stream().filter(this::pending).findFirst().orElse(null);
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
        boolean canUndo=!"INVALIDATED".equals(row.get("status")) && !latest.isEmpty() && !"CORRECT".equals(events.isEmpty()?null:events.getFirst().get("kind")) && id.equals(latest.getFirst().get("session_id"))
            && Objects.equals(row.get("version"),latest.getFirst().get("session_version_after"))
            && latest.getFirst().get("review_version").toString().equals(latest.getFirst().get("scored_version"));
        var round=jdbc.queryForMap("""
            select count(distinct e.card_id) filter(where e.before_state='null'::jsonb) as "newCount",
            count(distinct e.card_id) filter(where (e.after_state->>'firstLearnedAt')::timestamptz < ?::date at time zone ?) as "reviewCount"
            from review_event e join session_item i on i.user_id=e.user_id and i.id=e.session_item_id
            where e.user_id=? and i.session_id=? and e.kind in ('RATE','CORRECT')
            and not exists(select 1 from review_event u where u.user_id=e.user_id and u.undo_of=e.id)
            """,row.get("learning_date"),time.learningZone().getId(),user,id);
        var attention=jdbc.queryForList("""
            select distinct c.id,c.question,r.latest_rating as "latestRating",r.lapse_count as "lapseCount"
            from session_item i join card c on c.user_id=i.user_id and c.id=i.card_id
            join review_state r on r.user_id=c.user_id and r.card_id=c.id
            where i.user_id=? and i.session_id=? and r.latest_rating in ('FORGOT','VAGUE')
            and exists(select 1 from review_event e where e.user_id=i.user_id and e.session_item_id=i.id and e.kind in ('RATE','CORRECT')
                and not exists(select 1 from review_event u where u.user_id=e.user_id and u.undo_of=e.id)) order by c.id
            """,user,id);
        return obj("id",id,"version",row.get("version"),"mode",row.get("mode"),"status","INVALIDATED".equals(row.get("status"))?"INVALIDATED":current==null?"COMPLETED":"ACTIVE","learningDate",row.get("learning_date").toString(),
            "today",time.today(),"expired","INVALIDATED".equals(row.get("status")) || !row.get("learning_date").toString().equals(time.today().toString()),
            "items",items,"current",current,"review",state,"events",events,"canUndo",canUndo,
            "round",obj("newCount",round.get("newCount"),"reviewCount",round.get("reviewCount"),"attention",attention),
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
    /** Compare the immutable baseline, rather than silently treating a stale score as another practice. */
    private MutationService.Change competingScore(UUID user,UUID id,LearningController.Write c,long sv) throws IOException {
        if(!"RATE".equals(c.action())) return null;
        var p=c.payload();
        if(!confirmed(p,"answerRevealed") || !p.path("expectedReviewVersion").isIntegralNumber()
            || !p.path("expectedReviewVersion").canConvertToLong() || p.path("expectedReviewVersion").asLong()<0) invalid();
        Rating rating;
        try { rating=Rating.valueOf(p.path("rating").asText()); } catch(IllegalArgumentException e) { throw new ApiFailure(ErrorCode.INVALID_REQUEST); }
        var items=jdbc.queryForList("select id,card_id,expected_review_version from session_item where user_id=? and session_id=? and id::text=?",
            user,id,p.path("itemId").asText());
        if(items.isEmpty()) return null;
        var item=items.getFirst(); UUID card=(UUID)item.get("card_id");
        long expected=p.path("expectedReviewVersion").asLong();
        if(expected!=((Number)item.get("expected_review_version")).longValue() || cardVersion(user,card)==expected) return null;
        var events=jdbc.queryForList("""
            select * from review_event e where user_id=? and card_id=? and kind='RATE'
            and (after_state->>'version')::bigint=?
            and not exists(select 1 from review_event u where u.user_id=e.user_id and u.undo_of=e.id)
            """,user,card,expected+1);
        if(events.isEmpty()) return null;
        var event=events.getFirst(); var current=state(user,card);
        if(rating.name().equals(event.get("rating")))
            return new MutationService.Change(obj("duplicateScore",true,"eventId",event.get("id"),"dueDate",current==null?null:current.dueDate(),"session",session(user,id)),sv,false);
        UUID conflict=UUID.randomUUID();
        var server=obj("eventId",event.get("id"),"rating",event.get("rating"),"learningDate",event.get("learning_date").toString(),
            "afterState",json(event.get("after_state")),"current",current);
        jdbc.update("""
            insert into sync_conflict(id,user_id,entity_id,entity_type,client_content,server_content,data_epoch,operation_id)
            values(?,?,?,'REVIEW',?::jsonb,?::jsonb,?,?)
            """,conflict,user,card,mapper.writeValueAsString(obj("sessionId",id,"payload",p)),mapper.writeValueAsString(server),c.dataEpoch(),c.operationId());
        return new MutationService.Change(obj("conflict",conflictView(user,conflict),"session",session(user,id)),sv,true);
    }
    private Map<String,Object> conflictRow(UUID user,UUID id) {
        var rows=jdbc.queryForList("select * from sync_conflict where user_id=? and id=? and entity_type='REVIEW'",user,id);
        if(rows.isEmpty()) throw new ApiFailure(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }
    public Map<String,Object> conflictView(UUID user,UUID id) {
        var row=conflictRow(user,id);
        return obj("id",id,"cardId",row.get("entity_id"),"dataEpoch",row.get("data_epoch"),"operationId",row.get("operation_id"),
            "version",row.get("version"),"resolved",row.get("resolved"),"resolution",row.get("resolution"),
            "local",json(row.get("client_content")),"server",json(row.get("server_content")));
    }
    public JsonNode resolve(UUID user,UUID id,LearningController.Resolve c) throws IOException {
        return mutations.execute(user,new MutationService.Command(c.operationId(),c.dataEpoch()),"learning.resolve",obj("id",id,"command",c),()->{
            var row=conflictRow(user,id);
            if(!Objects.equals(c.expectedVersion(),row.get("version")) || Boolean.TRUE.equals(row.get("resolved")))
                throw new ApiFailure(ErrorCode.VERSION_CONFLICT,Map.of("current",conflictView(user,id)));
            if(!c.dataEpoch().equals(row.get("data_epoch"))) throw new ApiFailure(ErrorCode.EPOCH_EXPIRED);
            if(!Set.of("CURRENT","CANDIDATE").contains(c.choice())) invalid();
            UUID card=(UUID)row.get("entity_id");long version=cardVersion(user,card);
            if("CANDIDATE".equals(c.choice())) {
                var remote=json(row.get("server_content"));var local=json(row.get("client_content"));
                var events=jdbc.queryForList("select * from review_event where user_id=? and id=?",user,UUID.fromString(remote.path("eventId").asText()));
                if(events.isEmpty()) throw new ApiFailure(ErrorCode.NOT_FOUND);
                var event=events.getFirst();
                State old=mapper.treeToValue(json(event.get("after_state")),State.class);
                State before=mapper.treeToValue(json(event.get("before_state")),State.class);
                // A correction replaces this exact score. Later practice, undo or a new learning day requires fresh review.
                if(c.expectedReviewVersion()!=version || old.version()!=version || !event.get("learning_date").toString().equals(time.today().toString()))
                    throw new ApiFailure(ErrorCode.VERSION_CONFLICT,obj("current",state(user,card),"conflict",conflictView(user,id)));
                var rating=Rating.valueOf(local.at("/payload/rating").asText());
                var policy=policies.original(json(event.get("algorithm_parameters")),event.get("algorithm_version").toString());
                var corrected=ReviewRules.rate(before,rating,old.lastReviewedAt(),time.learningZone(),policy.intervals(),version+1);
                UUID item=(UUID)event.get("session_item_id");
                var originalItem=jdbc.queryForMap("select session_id,is_replay from session_item where user_id=? and id=?",user,item);
                UUID sessionId=(UUID)originalItem.get("session_id");
                long sv=((Number)sessionRow(user,sessionId).get("version")).longValue();
                var command=new LearningController.Write(c.operationId(),c.dataEpoch(),sv,"RATE",local.path("payload"));
                UUID correction=UUID.randomUUID();
                insertEvent(user,command,correction,card,item,before,corrected.state(),corrected.replay(),(UUID)event.get("id"),queue(user,sessionId),sv+1,"CORRECT",policy.algorithmVersion(),mapper.valueToTree(policy));
                saveState(user,card,corrected.state(),version+1);
                // Only an unconsumed automatic replay may change; a consumed replay would have advanced review_version.
                jdbc.update("delete from review_replay_allowance where user_id=? and event_id=?",user,event.get("id"));
                jdbc.update("delete from session_item where user_id=? and session_id=? and card_id=? and is_replay=true and not completed",user,sessionId,card);
                if(rating==Rating.FORGOT && !Boolean.TRUE.equals(originalItem.get("is_replay"))) {
                    int reserved=jdbc.update("insert into review_replay_allowance(user_id,card_id,learning_date,session_id,event_id) values(?,?,?,?,?) on conflict do nothing",
                        user,card,time.today(),sessionId,correction);
                    if(reserved==1) jdbc.update("""
                        insert into session_item(id,user_id,session_id,card_id,sort_order,expected_review_version,is_replay,replay_count,item_kind)
                        values(?,?,?,?,(select coalesce(max(sort_order),-1)+1 from session_item where user_id=? and session_id=?),?,true,1,'REPLAY')
                        """,UUID.randomUUID(),user,sessionId,card,user,sessionId,version+1);
                }
                advance(user,sessionId,sv+1);
            }
            jdbc.update("update sync_conflict set resolved=true,resolution=?,version=version+1 where user_id=? and id=?",c.choice(),user,id);
            return new MutationService.Change(obj("conflict",conflictView(user,id)),c.expectedVersion()+1,true);
        });
    }
    public JsonNode write(UUID user,UUID id,LearningController.Write command) throws IOException {
        return mutations.execute(user,new MutationService.Command(command.operationId(),command.dataEpoch()),"learning.write",
            obj("id",id,"command",command),()->{
                var session=sessionRow(user,id);
                if("INVALIDATED".equals(session.get("status"))) throw new ApiFailure(ErrorCode.SESSION_EXPIRED,Map.of("current",session(user,id)));
                long sv=((Number)session.get("version")).longValue();
                var duplicate=competingScore(user,id,command,sv);
                if(duplicate!=null) return duplicate;
                if(command.expectedVersion()==null || command.expectedVersion()!=sv) conflict(user,id);
                if(!session.get("learning_date").toString().equals(time.today().toString()) || "INVALIDATED".equals(session.get("status")))
                    throw new ApiFailure(ErrorCode.SESSION_EXPIRED,Map.of("current",session(user,id)));
                var p=command.payload();
                if("UNDO".equals(command.action())) return undo(user,id,command,sv);
                var items=queue(user,id);
                var current=items.stream().filter(this::pending).findFirst().orElseThrow(()->new ApiFailure(ErrorCode.SESSION_EXPIRED));
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
                var policy=policies.active(user);
                State after; boolean replay;
                if("RELEARN".equals(command.action())) {
                    if(before==null || !confirmed(p,"confirmed")) invalid();
                    after=new State(before.firstLearnedAt(),before.lastReviewedAt(),time.today(),-1,before.latestRating(),before.lapseCount(),version+1);
                    replay=false;
                } else {
                    Rating rating;
                    try { rating=Rating.valueOf(p.path("rating").asText()); } catch(IllegalArgumentException e) { throw new ApiFailure(ErrorCode.INVALID_REQUEST); }
                    var result=ReviewRules.rate(before,rating,now,time.learningZone(),policy.intervals(),version+1);
                    after=result.state(); replay=result.replay();
                    if(before!=null && before.dueDate().isAfter(time.today()) && !replay && !confirmed(p,"confirmEarly"))
                        throw new ApiFailure(ErrorCode.EARLY_REVIEW_CONFIRMATION,Map.of("dueDate",before.dueDate()));
                }
                UUID event=UUID.randomUUID();
                insertEvent(user,command,event,card,item,before,after,replay,null,items,sv+1,command.action(),policy.algorithmVersion(),mapper.valueToTree(policy));
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
                                insert into session_item(id,user_id,session_id,card_id,sort_order,expected_review_version,is_replay,replay_count,item_kind)
                                values(?,?,?,?,?,?,true,1,'REPLAY')
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
        if(after.version()!=version || "CORRECT".equals(event.get("kind"))) conflict(user,id);
        var oldQueue=json(event.get("before_queue"));
        UUID compensation=UUID.randomUUID();
        insertEvent(user,command,compensation,card,(UUID)event.get("session_item_id"),after,before,true,(UUID)event.get("id"),queue(user,id),sv+1,"UNDO",event.get("algorithm_version").toString(),json(event.get("algorithm_parameters")));
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
                             UUID undo,List<Map<String,Object>> queue,long sv,String kind,String algorithmVersion,JsonNode parameters) throws IOException {
        jdbc.update("""
            insert into review_event(id,user_id,data_epoch,operation_id,card_id,session_item_id,rating,before_state,after_state,
            algorithm_version,learning_date,is_replay,undo_of,before_queue,session_version_after,kind,algorithm_parameters)
            values(?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?::jsonb,?,?,?::jsonb)
            """,event,user,c.dataEpoch(),c.operationId(),card,item,(after!=null?after:before).latestRating().name(),
            mapper.writeValueAsString(before),mapper.writeValueAsString(after),algorithmVersion,time.today(),replay,undo,mapper.writeValueAsString(queue),sv,kind,mapper.writeValueAsString(parameters));
    }
    private void advance(UUID user,UUID id,long version) {
        jdbc.update("update learning_session set version=?,status=? where user_id=? and id=?",version,
            queue(user,id).stream().anyMatch(this::pending)?"ACTIVE":"COMPLETED",user,id);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> summary(UUID user) {
        // Effective history, never increment-only counters; compensation rows are excluded.
        var today=time.today();
        var counts=jdbc.queryForMap(EffectiveReviews.CTE+"""
            select (select count(*) from review_state where user_id=?) as learned,
            (select count(distinct learning_date) from effective where learning_date between ? and ?) as "weekLearningDays",
            (select count(*) from firsts where first_day=?) as "todayNew",
            (select count(distinct e.card_id) from effective e join firsts f on f.card_id=e.card_id where e.learning_date=? and f.first_day<?) as "todayReview"
            """,user,user,today.minusDays(6),today,today,today,today);
        return obj("learningDate",today,"learned",counts.get("learned"),"todayNew",counts.get("todayNew"),"todayReview",counts.get("todayReview"),"weekLearningDays",counts.get("weekLearningDays"));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> preferences(UUID user) {
        var rows=jdbc.queryForList("select * from learning_preference where user_id=?",user);
        if(rows.isEmpty()) return obj("saved",false,"version",0L,"dailyNewCount",5,"reviewBatchSize",20,"referenceMinutes",null,"chapterIds",List.of());
        var p=rows.getFirst();
        return obj("saved",true,"version",p.get("version"),"dailyNewCount",p.get("daily_new_count"),"reviewBatchSize",p.get("review_batch_size"),
            "referenceMinutes",p.get("reference_minutes"),"chapterIds",jdbc.queryForList("select chapter_id from learning_preference_chapter where user_id=? order by chapter_id",UUID.class,user));
    }
    public JsonNode savePreferences(UUID user,LearningController.Preferences c) throws IOException {
        return mutations.execute(user,new MutationService.Command(c.operationId(),c.dataEpoch()),"learning.preferences",c,()->{
            var p=preferences(user);
            if(!Objects.equals(c.expectedVersion(),p.get("version"))) throw new ApiFailure(ErrorCode.VERSION_CONFLICT,Map.of("current",p));
            if(c.dailyNewCount()==null || c.dailyNewCount()<0 || c.dailyNewCount()>100 || c.reviewBatchSize()==null || c.reviewBatchSize()<1 || c.reviewBatchSize()>100
                || c.referenceMinutes()!=null && (c.referenceMinutes()<1 || c.referenceMinutes()>1440) || c.chapterIds()==null || c.chapterIds().size()>1000
                || c.chapterIds().stream().anyMatch(Objects::isNull) || new HashSet<>(c.chapterIds()).size()!=c.chapterIds().size()) invalid();
            for(var chapter:c.chapterIds()) if(jdbc.queryForObject("select count(*) from chapter where user_id=? and id=? and check_status='IMPORTED'",Integer.class,user,chapter)!=1)
                throw new ApiFailure(ErrorCode.NOT_FOUND);
            jdbc.update("""
                insert into learning_preference(id,user_id,daily_new_count,review_batch_size,reference_minutes,version)
                values(?,?,?,?,?,?) on conflict(user_id) do update set daily_new_count=excluded.daily_new_count,
                review_batch_size=excluded.review_batch_size,reference_minutes=excluded.reference_minutes,version=excluded.version
                """,UUID.randomUUID(),user,c.dailyNewCount(),c.reviewBatchSize(),c.referenceMinutes(),c.expectedVersion()+1);
            UUID preference=jdbc.queryForObject("select id from learning_preference where user_id=?",UUID.class,user);
            jdbc.update("delete from learning_preference_chapter where user_id=?",user);
            for(var chapter:c.chapterIds()) jdbc.update("insert into learning_preference_chapter(user_id,preference_id,chapter_id) values(?,?,?)",user,preference,chapter);
            // Reject devices still presenting a queue calculated using an earlier preference.
            jdbc.update("update learning_session set version=version+1 where user_id=? and mode<>'MANUAL' and learning_date=?",user,time.today());
            return new MutationService.Change(obj("preferences",preferences(user)),c.expectedVersion()+1,true);
        });
    }
    private int remainingNew(UUID user) {
        return Math.max(0,((Number)preferences(user).get("dailyNewCount")).intValue()-((Number)summary(user).get("todayNew")).intValue());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> today(UUID user) {
        var p=preferences(user);var counts=summary(user);
        var chapters=jdbc.queryForList("""
            select ch.id,ch.name,m.name as "moduleName",d.id as "deckId",d.name as "deckName",
            exists(select 1 from learning_preference_chapter pc where pc.user_id=ch.user_id and pc.chapter_id=ch.id) as selected,
            count(c.id) as total,count(r.card_id) as learned,
            count(r.card_id) filter(where r.due_date<=?) as due,
            count(r.card_id) filter(where r.latest_rating in ('FORGOT','VAGUE')) as weak
            from chapter ch join module m on m.user_id=ch.user_id and m.id=ch.module_id
            join deck d on d.user_id=m.user_id and d.id=m.deck_id
            left join card c on c.user_id=ch.user_id and c.chapter_id=ch.id
            left join review_state r on r.user_id=c.user_id and r.card_id=c.id
            where ch.user_id=? and ch.check_status='IMPORTED'
            group by ch.id,ch.user_id,ch.name,m.name,d.name,d.id,m.sort_order,m.id,ch.sort_order
            order by d.id,m.sort_order,m.id,ch.sort_order,ch.id
            """,time.today(),user);
        long due=0,unlearned=0;
        for(var ch:chapters) if(Boolean.TRUE.equals(ch.get("selected"))) {
            due+=((Number)ch.get("due")).longValue();unlearned+=((Number)ch.get("total")).longValue()-((Number)ch.get("learned")).longValue();
        }
        var continuing=new ArrayList<Map<String,Object>>();
        for(var id:jdbc.queryForList("select id from learning_session where user_id=? and learning_date=? and status<>'INVALIDATED' order by created_at desc,id",UUID.class,user,time.today())) {
            var s=session(user,id);
            if(s.get("current")!=null) continuing.add(obj("id",id,"mode",s.get("mode"),"remaining",((List<?>)s.get("items")).stream().map(i->(Map<String,Object>)i).filter(this::pending).count()));
        }
        return obj("learningDate",time.today(),"learningTimezone",time.learningZone().getId(),"preferences",p,"summary",counts,
            "remainingNew",remainingNew(user),"availableNew",unlearned,"dueTotal",due,"chapters",chapters,"continuing",continuing);
    }
    public JsonNode daily(UUID user,LearningController.Plan c) throws IOException {
        return mutations.execute(user,new MutationService.Command(c.operationId(),c.dataEpoch()),"learning.daily",c,()->{
            if(!Set.of("MIXED","REVIEW","NEW").contains(c.mode())) invalid();
            var p=preferences(user);
            if(!Boolean.TRUE.equals(p.get("saved"))) invalid();
            for(var old:jdbc.queryForList("select id from learning_session where user_id=? and learning_date=? and mode=? and status<>'INVALIDATED' order by created_at desc,id",UUID.class,user,time.today(),c.mode())) {
                var s=session(user,old);
                if(s.get("current")!=null) return new MutationService.Change(obj("session",s),((Number)s.get("version")).longValue(),false);
            }
            UUID id=UUID.randomUUID();
            jdbc.update("insert into learning_session(id,user_id,learning_date,mode) values(?,?,?,?)",id,user,time.today(),c.mode());
            var candidates=new ArrayList<Map<String,Object>>();
            if(!"NEW".equals(c.mode())) candidates.addAll(jdbc.queryForList("""
                select c.id,c.review_version,'REVIEW' as kind from card c
                join chapter ch on ch.user_id=c.user_id and ch.id=c.chapter_id and ch.check_status='IMPORTED'
                join learning_preference_chapter pc on pc.user_id=c.user_id and pc.chapter_id=c.chapter_id
                join review_state r on r.user_id=c.user_id and r.card_id=c.id
                where c.user_id=? and r.due_date<=?
                order by r.due_date,case r.latest_rating when 'FORGOT' then 0 when 'VAGUE' then 1 else 2 end,c.id limit ?
                """,user,time.today(),p.get("reviewBatchSize")));
            if(!"REVIEW".equals(c.mode())) candidates.addAll(jdbc.queryForList("""
                select c.id,c.review_version,'NEW' as kind from card c
                join chapter ch on ch.user_id=c.user_id and ch.id=c.chapter_id and ch.check_status='IMPORTED'
                join module m on m.user_id=ch.user_id and m.id=ch.module_id
                join draft_card draft on draft.user_id=c.user_id and draft.id=c.draft_id
                join learning_preference_chapter pc on pc.user_id=c.user_id and pc.chapter_id=c.chapter_id
                where c.user_id=? and not exists(select 1 from review_state r where r.user_id=c.user_id and r.card_id=c.id)
                order by m.deck_id,m.sort_order,m.id,ch.sort_order,ch.id,draft.sort_order,c.id limit ?
                """,user,remainingNew(user)));
            int order=0;
            for(var card:candidates) jdbc.update("insert into session_item(id,user_id,session_id,card_id,sort_order,expected_review_version,item_kind) values(?,?,?,?,?,?,?)",
                UUID.randomUUID(),user,id,card.get("id"),order++,card.get("review_version"),card.get("kind"));
            advance(user,id,0);
            return new MutationService.Change(obj("session",session(user,id)),0,true);
        });
    }
    private void conflict(UUID user,UUID id) { throw new ApiFailure(ErrorCode.VERSION_CONFLICT,Map.of("current",session(user,id))); }
    private static boolean confirmed(JsonNode p,String field) { return p.path(field).isBoolean() && p.path(field).asBoolean(); }
    private static void invalid() { throw new ApiFailure(ErrorCode.INVALID_REQUEST); }
    private JsonNode json(Object v) { try { return mapper.readTree(v.toString()); } catch(IOException e) { throw new IllegalStateException(e); } }
    private static Map<String,Object> obj(Object... fields) { var m=new LinkedHashMap<String,Object>();for(int i=0;i<fields.length;i+=2)m.put((String)fields[i],fields[i+1]);return m; }
}
