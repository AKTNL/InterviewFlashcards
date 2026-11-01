package com.interviewflashcards.auth;

import com.interviewflashcards.common.web.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersonalInviteService {
    private final JdbcTemplate jdbc;
    private final RegistrationProperties registration;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    public PersonalInviteService(JdbcTemplate jdbc, RegistrationProperties registration, Clock clock) {
        this.jdbc=jdbc; this.registration=registration; this.clock=clock;
    }
    // Shared order for registration, generation, revocation and maintenance permission changes.
    public void lock() { jdbc.queryForObject("select id from registration_guard where id=1 for update",Integer.class); }
    public void checkOpen() {
        if (!registration.enabled()) throw new ApiFailure(ErrorCode.REGISTRATION_CLOSED);
        if (jdbc.queryForObject("select (select count(*) from registration_use)+(select count(*) from admin_account_allocation)",Long.class)>=registration.maxAccounts())
            throw new ApiFailure(ErrorCode.REGISTRATION_LIMITED);
    }
    private int remaining(UUID user) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select enabled from app_user where id=?",Boolean.class,user))) return 0;
        var rows=jdbc.queryForList("select * from invitation_permission where user_id=?",user);
        if(rows.isEmpty() || !(Boolean)rows.getFirst().get("enabled")) return 0;
        var row=rows.getFirst();
        return Math.max(0,((Number)row.get("total_limit")).intValue()-((Number)row.get("successful_count")).intValue());
    }
    Map<String,Object> valid(String token) {
        checkOpen();
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}")) throw new ApiFailure(ErrorCode.INVITE_INVALID);
        var rows=jdbc.queryForList("select * from registration_invite where token_digest=? for update",AccountRules.digest(token));
        if(rows.isEmpty()) throw new ApiFailure(ErrorCode.INVITE_INVALID);
        var row=rows.getFirst();
        if(!state(row).equals("AVAILABLE") || (row.get("inviter_id") instanceof UUID owner && remaining(owner)==0))
            throw new ApiFailure(ErrorCode.INVITE_INVALID);
        return row;
    }
    @Transactional
    public void validate(String token) { lock(); valid(token); }
    @Transactional
    public Overview overview(UUID user) {
        lock();
        var rows=jdbc.queryForList("select * from invitation_permission where user_id=?",user);
        boolean allowed=!rows.isEmpty() && (Boolean)rows.getFirst().get("enabled");
        int used=rows.isEmpty()?0:((Number)rows.getFirst().get("successful_count")).intValue();
        int total=rows.isEmpty()?0:((Number)rows.getFirst().get("total_limit")).intValue();
        var invites=jdbc.queryForList("select * from registration_invite where inviter_id=? order by created_at desc,id desc limit 1",user);
        Invite latest=invites.isEmpty()?null:view(invites.getFirst());
        boolean full=jdbc.queryForObject("select (select count(*) from registration_use)+(select count(*) from admin_account_allocation)",Long.class)>=registration.maxAccounts();
        String reason=!registration.enabled()?"注册暂未开放，请联系维护者。":!allowed?"尚无邀请权限，请联系维护者。":
            full?"全站注册名额已满，请联系维护者。":remaining(user)==0?"成功邀请额度已用尽，请联系维护者。":
            latest!=null && latest.status().equals("AVAILABLE")?"已有一枚有效邀请码。":null;
        return new Overview(allowed,total,used,Math.max(0,total-used),reason==null,reason,latest);
    }
    @Transactional
    public Generated generate(UUID user) {
        lock(); checkOpen();
        if(remaining(user)==0) throw new ApiFailure(ErrorCode.INVITATION_NOT_ALLOWED);
        var active=jdbc.queryForList("select * from registration_invite where inviter_id=? and not revoked and used_count<max_uses and expires_at>?",user,java.sql.Timestamp.from(clock.instant()));
        if(!active.isEmpty()) throw new ApiFailure(ErrorCode.INVITE_ALREADY_ACTIVE);
        byte[] entropy=new byte[32];random.nextBytes(entropy);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        UUID id=UUID.randomUUID(); Instant now=clock.instant(), expires=now.plus(Duration.ofDays(7));
        jdbc.update("insert into registration_invite(id,token_digest,expires_at,max_uses,inviter_id,invite_source,created_at) values(?,?,?,1,?,'PERSONAL',?)",
            id,AccountRules.digest(token),java.sql.Timestamp.from(expires),user,java.sql.Timestamp.from(now));
        return new Generated(new Invite(id,"邀请 · "+id.toString().substring(0,8),expires,1,0,"AVAILABLE"),token);
    }
    @Transactional
    public void revoke(UUID user,UUID invite) {
        lock();
        if(jdbc.update("update registration_invite set revoked=true where id=? and inviter_id=?",invite,user)!=1)
            throw new ApiFailure(ErrorCode.NOT_FOUND);
    }
    private String state(Map<String,Object> row) {
        if((Boolean)row.get("revoked")) return "REVOKED";
        if(((Number)row.get("used_count")).intValue()>=((Number)row.get("max_uses")).intValue()) return "USED";
        if(!((java.sql.Timestamp)row.get("expires_at")).toInstant().isAfter(clock.instant())) return "EXPIRED";
        return "AVAILABLE";
    }
    private Invite view(Map<String,Object> row) {
        UUID id=(UUID)row.get("id"); int used=((Number)row.get("used_count")).intValue();
        return new Invite(id,"邀请 · "+id.toString().substring(0,8),((java.sql.Timestamp)row.get("expires_at")).toInstant(),
            Math.max(0,((Number)row.get("max_uses")).intValue()-used),used,state(row));
    }
    public record Invite(UUID id,String label,Instant expiresAt,int remainingUses,int usedCount,String status) {}
    public record Overview(boolean allowed,int totalLimit,int successfulCount,int remainingQuota,boolean canGenerate,String reason,Invite latest) {}
    public record Generated(Invite invite,String token) {
        @Override public String toString() { return "Generated[redacted]"; }
    }
}
