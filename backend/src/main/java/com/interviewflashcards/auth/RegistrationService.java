package com.interviewflashcards.auth;

import com.interviewflashcards.common.config.AppProperties;
import com.interviewflashcards.common.web.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final AppProperties properties;
    private final PersonalInviteService invites;
    public RegistrationService(JdbcTemplate jdbc, PasswordEncoder passwords, AppProperties properties, PersonalInviteService invites) {
        this.jdbc=jdbc; this.passwords=passwords; this.properties=properties; this.invites=invites;
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=3600000)
    public void cleanupRateBuckets() { jdbc.update("delete from auth_rate_bucket where window_start<current_timestamp-interval '1 day'"); }
    @Transactional
    public void register(String token, String rawName, String password, String nickname) {
        invites.lock();
        invites.checkOpen();
        String name=AccountRules.username(rawName); AccountRules.password(password);
        if (nickname!=null && (nickname.length()>60 || nickname.codePoints().anyMatch(Character::isISOControl))) throw new ApiFailure(ErrorCode.VALIDATION_ERROR);
        var invite=invites.valid(token);
        if (name.equals(AccountRules.normalize(properties.account().username()))
                || jdbc.queryForObject("select count(*) from account_name where normalized=?",Integer.class,name)>0) throw new ApiFailure(ErrorCode.USERNAME_TAKEN);
        UUID user=UUID.randomUUID();
        // Unique reservation and all initialization roll back together, including the invite quota.
        try {
            jdbc.update("insert into app_user(id,username,password_hash,nickname,learning_timezone,account_origin) values(?,?,?,?,?,'REGISTERED')",
                user,name,passwords.encode(password),nickname==null || nickname.isBlank()?null:nickname.strip(),properties.learningTimezone());
            jdbc.update("insert into account_name(normalized,user_id) values(?,?)",name,user);
        } catch (org.springframework.dao.DuplicateKeyException e) { throw new ApiFailure(ErrorCode.USERNAME_TAKEN); }
        jdbc.update("insert into learning_preference(id,user_id) values(?,?)",UUID.randomUUID(),user);
        jdbc.update("insert into registration_use(invite_id,user_id) values(?,?)",invite.get("id"),user);
        jdbc.update("update registration_invite set used_count=used_count+1 where id=?",invite.get("id"));
        if(invite.get("inviter_id") instanceof UUID owner)
            jdbc.update("update invitation_permission set successful_count=successful_count+1 where user_id=?",owner);
    }
    @Transactional
    public void changePassword(UUID user, String current, String replacement) {
        AccountRules.password(replacement);
        var row=jdbc.queryForMap("select password_hash from app_user where id=? for no key update",user);
        if (current==null || !passwords.matches(current,(String)row.get("password_hash"))) throw new ApiFailure(ErrorCode.BAD_CREDENTIALS);
        if (passwords.matches(replacement,(String)row.get("password_hash"))) throw new ApiFailure(ErrorCode.PASSWORD_REUSED);
        jdbc.update("update app_user set password_hash=?,credential_version=credential_version+1,must_change_password=false,admin_version=admin_version+1 where id=?",passwords.encode(replacement),user);
    }
}
