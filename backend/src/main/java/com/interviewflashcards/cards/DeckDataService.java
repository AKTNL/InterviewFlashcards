package com.interviewflashcards.cards;

import com.fasterxml.jackson.databind.JsonNode;
import com.interviewflashcards.common.web.*;
import com.interviewflashcards.sync.MutationService;
import java.io.IOException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeckDataService {
    private final JdbcTemplate jdbc;
    private final MutationService mutations;
    public DeckDataService(JdbcTemplate jdbc, MutationService mutations) { this.jdbc=jdbc; this.mutations=mutations; }
    public List<Map<String,Object>> decks(UUID user) {
        return jdbc.queryForList("select id,name from deck where user_id=? order by name,id", user);
    }
    private static final String CHAPTERS="select ch.id from chapter ch join module m on m.user_id=ch.user_id and m.id=ch.module_id where ch.user_id=? and m.deck_id=?";
    private static final String CARDS="select id from card where user_id=? and chapter_id in ("+CHAPTERS+")";
    private Object[] args(UUID user, UUID deck) { return new Object[]{user,user,user,deck}; }
    private long count(String table, String column, UUID user, UUID deck) {
        return jdbc.queryForObject("select count(*) from "+table+" where user_id=? and "+column+" in ("+CARDS+")",Long.class,args(user,deck));
    }
    @Transactional
    public Map<String,Object> preview(UUID user, UUID deck) {
        var owner=jdbc.queryForMap("select data_epoch,revision from app_user where id=? for share",user);
        var rows=jdbc.queryForList("select name from deck where user_id=? and id=?",user,deck);
        if(rows.isEmpty()) throw new ApiFailure(ErrorCode.NOT_FOUND);
        return Map.of("deckId",deck,"name",rows.getFirst().get("name"),"dataEpoch",owner.get("data_epoch"),"revision",owner.get("revision"),
            "counts",Map.of("cards",jdbc.queryForObject("select count(*) from card where user_id=? and chapter_id in ("+CHAPTERS+")",Long.class,user,user,deck),
                "drafts",jdbc.queryForObject("select count(*) from draft_card where user_id=? and chapter_id in ("+CHAPTERS+")",Long.class,user,user,deck),
                "annotations",count("personal_annotation","card_id",user,deck),"learned",count("review_state","card_id",user,deck),
                "events",count("review_event","card_id",user,deck),
                "sessions",jdbc.queryForObject("select count(distinct session_id) from session_item where user_id=? and card_id in ("+CARDS+")",Long.class,args(user,deck))));
    }
    public JsonNode confirm(UUID user, UUID deck, DeckDataController.Confirm c) throws IOException {
        return mutations.execute(user,new MutationService.Command(c.operationId(),c.dataEpoch()),"deck.data",Map.of("deckId",deck,"command",c),()->{
            if(!c.confirmed() || !List.of("DELETE","RESET").contains(c.action())) throw new ApiFailure(ErrorCode.INVALID_REQUEST);
            var impact=preview(user,deck);
            if(!c.expectedRevision().equals(impact.get("revision"))) throw new ApiFailure(ErrorCode.DATA_PREVIEW_STALE);
            jdbc.update("update learning_session set status='INVALIDATED',version=version+1 where user_id=? and id in (select session_id from session_item where user_id=? and card_id in ("+CARDS+"))",user,user,user,user,deck);
            for(String table:List.of("review_replay_allowance","review_event","review_state"))
                jdbc.update("delete from "+table+" where user_id=? and card_id in ("+CARDS+")",args(user,deck));
            jdbc.update("delete from sync_conflict where user_id=? and entity_type='REVIEW' and entity_id in ("+CARDS+")",args(user,deck));
            jdbc.update("delete from session_item where user_id=? and card_id in ("+CARDS+")",args(user,deck));
            jdbc.update("update card set review_version=review_version+1 where user_id=? and chapter_id in ("+CHAPTERS+")",user,user,deck);
            if("DELETE".equals(c.action())) {
                for(String table:List.of("source_span","follow_up","card_tag","personal_annotation")) {
                    jdbc.update("delete from "+table+" where user_id=? and card_id in ("+CARDS+")",args(user,deck));
                    if(List.of("source_span","follow_up").contains(table)) jdbc.update("delete from "+table+" where user_id=? and draft_id in (select id from draft_card where user_id=? and chapter_id in ("+CHAPTERS+"))",user,user,user,deck);
                }
                jdbc.update("delete from card where user_id=? and chapter_id in ("+CHAPTERS+")",user,user,deck);
                for(String table:List.of("learning_preference_chapter","chapter_check_progress","draft_edit_operation","draft_card"))
                    jdbc.update("delete from "+table+" where user_id=? and chapter_id in ("+CHAPTERS+")",user,user,deck);
                jdbc.update("update import_job set deck_id=null,status='FAILED',stage='DELETED',error_code='DECK_DELETED',error_message='题库已删除；原 PDF 保留，可重试解析重新导入。',attempt_token=null,version=version+1 where user_id=? and deck_id=?",user,deck);
                jdbc.update("delete from chapter where user_id=? and id in ("+CHAPTERS+")",user,user,deck);
                jdbc.update("delete from module where user_id=? and deck_id=?",user,deck);
                jdbc.update("delete from deck where user_id=? and id=?",user,deck);
                jdbc.update("update learning_preference set version=version+1 where user_id=?",user);
            }
            return new MutationService.Change(Map.of("action",c.action(),"impact",impact),0,true);
        });
    }
}
