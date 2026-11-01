package com.interviewflashcards.groups;

import com.interviewflashcards.auth.AccountRules;
import com.interviewflashcards.common.time.LearningTime;
import com.interviewflashcards.common.web.*;
import com.interviewflashcards.learning.LearningService;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Live authorization only: never persist another member's summary in receipts or backups. */
@Service
@Transactional
public class GroupService {
    private final JdbcTemplate jdbc;
    private final LearningService learning;
    private final LearningTime time;
    public GroupService(JdbcTemplate jdbc,LearningService learning,LearningTime time) {
        this.jdbc=jdbc;this.learning=learning;this.time=time;
    }
    private Map<String,Object> group(UUID id,boolean writing) {
        var rows=jdbc.queryForList("select * from study_group where id=? "+(writing?"for update":"for share"),id);
        if(rows.isEmpty())throw new ApiFailure(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }
    private Map<String,Object> member(UUID user,UUID id) {
        var rows=jdbc.queryForList("select * from study_group_member where group_id=? and user_id=? and active",id,user);
        if(rows.isEmpty())throw new ApiFailure(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }
    private void owner(UUID user,Map<String,Object> g) {
        if(Boolean.TRUE.equals(g.get("closed")) || !user.equals(g.get("owner_id")))throw new ApiFailure(ErrorCode.NOT_FOUND);
    }
    public Map<String,Object> create(UUID user,GroupController.Create c) {
        if(!c.confirmed() || c.name()==null || c.name().isBlank() || c.name().strip().length()>80)throw new ApiFailure(ErrorCode.INVALID_REQUEST);
        jdbc.queryForMap("select id from app_user where id=? for no key update",user);
        var old=jdbc.queryForList("select * from study_group where id=?",c.id());
        if(!old.isEmpty()) {
            owner(user,old.getFirst());
            if(!c.name().strip().equals(old.getFirst().get("name")))throw new ApiFailure(ErrorCode.INVALID_REQUEST);
            return Map.of("id",c.id());
        }
        if(jdbc.queryForObject("select count(*) from study_group where owner_id=? and not closed",Integer.class,user)>=5)throw new ApiFailure(ErrorCode.GROUP_LIMITED);
        // ON CONFLICT also makes a guessed UUID owned by someone else harmless.
        if(jdbc.update("insert into study_group(id,owner_id,name) values(?,?,?) on conflict do nothing",c.id(),user,c.name().strip())!=1)throw new ApiFailure(ErrorCode.INVALID_REQUEST);
        jdbc.update("insert into study_group_member(group_id,user_id) values(?,?)",c.id(),user);
        return Map.of("id",c.id());
    }
    public Map<String,Object> list(UUID user) {
        return Map.of("groups",jdbc.queryForList("""
            select g.id,g.name,g.owner_id=? as owner from study_group g
            join study_group_member m on m.group_id=g.id where m.user_id=? and m.active and not g.closed order by g.created_at,g.id
            """,user,user),"incoming",jdbc.queryForList("""
            select i.id,i.group_id as "groupId",g.name,coalesce(nullif(u.nickname,''),u.username) as inviter,i.expires_at as "expiresAt"
            from study_group_invite i join study_group g on g.id=i.group_id join app_user u on u.id=g.owner_id
            where i.recipient_id=? and not i.revoked and not i.accepted and i.expires_at>? and not g.closed order by i.created_at desc
            """,user,Timestamp.from(time.now())));
    }
    public Map<String,Object> detail(UUID user,UUID id) {
        var g=group(id,false);if(Boolean.TRUE.equals(g.get("closed")))throw new ApiFailure(ErrorCode.NOT_FOUND);
        var me=member(user,id);
        var members=jdbc.queryForList("""
            select m.user_id as id,coalesce(nullif(u.nickname,''),u.username) as nickname,m.sharing
            from study_group_member m join app_user u on u.id=m.user_id where m.group_id=? and m.active order by m.user_id
            """,id);
        for(var m:members) {
            m.put("self",user.equals(m.get("id")));
            if(Boolean.TRUE.equals(m.get("sharing"))) {
                var s=learning.summary((UUID)m.get("id"));
                m.put("summary",Map.of("learningDate",s.get("learningDate"),"learningZone",time.learningZone().getId(),
                    "studiedToday",((Number)s.get("todayNew")).longValue()+((Number)s.get("todayReview")).longValue()>0,
                    "todayNew",s.get("todayNew"),"todayReview",s.get("todayReview"),"weekLearningDays",s.get("weekLearningDays")));
            }
            m.remove("id"); // Internal user IDs and login names are not exposed when a nickname exists.
        }
        var out=new LinkedHashMap<String,Object>();out.put("id",id);out.put("name",g.get("name"));out.put("owner",user.equals(g.get("owner_id")));
        out.put("sharing",me.get("sharing"));out.put("version",me.get("version"));out.put("members",members);
        if(user.equals(g.get("owner_id")))out.put("invites",jdbc.queryForList("""
            select i.id,u.username as recipient,i.accepted,i.revoked,i.expires_at as "expiresAt",i.expires_at<=? as expired
            from study_group_invite i join app_user u on u.id=i.recipient_id where i.group_id=? order by i.created_at desc limit 100
            """,Timestamp.from(time.now()),id));
        return out;
    }
    public Map<String,Object> invite(UUID user,UUID id,GroupController.Invite c) {
        owner(user,group(id,true));
        if(c.expiresInHours()<1 || c.expiresInHours()>168)throw new ApiFailure(ErrorCode.INVALID_REQUEST);
        var users=jdbc.queryForList("select id from app_user where username=? and id<>? and password_hash<>'!'",AccountRules.normalize(c.recipient()),user);
        if(users.isEmpty())throw new ApiFailure(ErrorCode.SHARE_RECIPIENT_INVALID);
        UUID recipient=(UUID)users.getFirst().get("id");
        var old=jdbc.queryForList("select group_id,recipient_id from study_group_invite where id=?",c.id());
        if(!old.isEmpty()) {
            if(!id.equals(old.getFirst().get("group_id")) || !recipient.equals(old.getFirst().get("recipient_id")))throw new ApiFailure(ErrorCode.INVALID_REQUEST);
            return Map.of("id",c.id());
        }
        if(jdbc.queryForObject("select count(*) from study_group_invite where group_id=? and not revoked and not accepted and expires_at>?",Integer.class,id,Timestamp.from(time.now()))>=50)throw new ApiFailure(ErrorCode.GROUP_LIMITED);
        if(jdbc.update("insert into study_group_invite(id,group_id,recipient_id,expires_at) values(?,?,?,?) on conflict do nothing",c.id(),id,recipient,Timestamp.from(time.now().plus(Duration.ofHours(c.expiresInHours()))))!=1)throw new ApiFailure(ErrorCode.INVALID_REQUEST);
        return Map.of("id",c.id());
    }
    public void revoke(UUID user,UUID id,UUID invite) {
        owner(user,group(id,true));
        if(jdbc.update("update study_group_invite set revoked=true where group_id=? and id=?",id,invite)!=1)throw new ApiFailure(ErrorCode.NOT_FOUND);
    }
    public void join(UUID user,UUID id,UUID invite,GroupController.Join c) {
        var g=group(id,true);
        var rows=jdbc.queryForList("select * from study_group_invite where group_id=? and id=? and recipient_id=?",id,invite,user);
        if(rows.isEmpty())throw new ApiFailure(ErrorCode.NOT_FOUND);
        if(!c.confirmed())throw new ApiFailure(ErrorCode.INVALID_REQUEST);
        var i=rows.getFirst();
        if(Boolean.TRUE.equals(g.get("closed")) || Boolean.TRUE.equals(i.get("revoked")))throw new ApiFailure(ErrorCode.GROUP_INVITE_UNAVAILABLE);
        // Replaying an accepted invitation never restores a departed membership or consent.
        if(Boolean.TRUE.equals(i.get("accepted"))) {member(user,id);return;}
        if(!((Timestamp)i.get("expires_at")).toInstant().isAfter(time.now()))throw new ApiFailure(ErrorCode.GROUP_INVITE_UNAVAILABLE);
        if(jdbc.queryForObject("select count(*) from study_group_member where group_id=? and active",Integer.class,id)>=50)throw new ApiFailure(ErrorCode.GROUP_LIMITED);
        jdbc.update("""
            insert into study_group_member(group_id,user_id) values(?,?) on conflict(group_id,user_id) do update
            set active=true,sharing=false,version=study_group_member.version+1 where not study_group_member.active
            """,id,user);
        jdbc.update("update study_group_invite set accepted=true where id=?",invite);
    }
    public Map<String,Object> consent(UUID user,UUID id,GroupController.Consent c) {
        var g=group(id,true);if(Boolean.TRUE.equals(g.get("closed")))throw new ApiFailure(ErrorCode.NOT_FOUND);
        var m=member(user,id);
        if(!Objects.equals(c.expectedVersion(),m.get("version")))throw new ApiFailure(ErrorCode.VERSION_CONFLICT);
        jdbc.update("update study_group_member set sharing=?,version=version+1 where group_id=? and user_id=?",c.sharing(),id,user);
        return Map.of("sharing",c.sharing(),"version",c.expectedVersion()+1);
    }
    public void leave(UUID user,UUID id) {
        var g=group(id,true);
        if(user.equals(g.get("owner_id"))) {
            jdbc.update("update study_group set closed=true where id=?",id);
            jdbc.update("update study_group_member set active=false,sharing=false,version=version+1 where group_id=? and active",id);
            jdbc.update("update study_group_invite set revoked=true where group_id=?",id);
        } else {
            // Repeated leave is harmless, but outsiders cannot probe the group.
            if(jdbc.queryForObject("select count(*) from study_group_member where group_id=? and user_id=?",Integer.class,id,user)==0)throw new ApiFailure(ErrorCode.NOT_FOUND);
            jdbc.update("update study_group_member set active=false,sharing=false,version=version+1 where group_id=? and user_id=? and active",id,user);
            jdbc.update("update study_group_invite set revoked=true where group_id=? and recipient_id=?",id,user);
        }
    }
}
