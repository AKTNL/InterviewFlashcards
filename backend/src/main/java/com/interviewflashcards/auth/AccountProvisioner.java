package com.interviewflashcards.auth;

import com.interviewflashcards.common.config.AppProperties;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AccountProvisioner implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final AppProperties properties;
    public AccountProvisioner(JdbcTemplate jdbc, AppProperties properties) { this.jdbc = jdbc; this.properties = properties; }
    @Override @Transactional public void run(ApplicationArguments args) {
        var account = properties.account();
        if (account.username().length() > 100 || !account.username().equals(account.username().strip())
                || !account.passwordHash().matches("\\$2[aby]\\$(1[0-6])\\$[./A-Za-z0-9]{53}")) {
            throw new IllegalStateException("Configure ACCOUNT_USERNAME and a BCrypt hash with cost 10..16");
        }
        // Bind once. Later environment changes must never overwrite credentials or claim another identity.
        if (jdbc.queryForObject("select count(*) from preset_identity",Integer.class)>0) return;
        var existing=jdbc.queryForList("select id,account_origin from app_user where username=? for no key update",account.username());
        UUID id;
        if (!existing.isEmpty()) {
            if (!existing.getFirst().get("account_origin").equals("LEGACY")) throw new IllegalStateException("Preset identity requires explicit operator migration");
            id=(UUID)existing.getFirst().get("id");
            jdbc.update("update app_user set account_origin='PRESET' where id=?",id);
        } else {
            id=UUID.randomUUID();
            jdbc.update("insert into app_user(id,username,password_hash,learning_timezone,account_origin) values(?,?,?,?,'PRESET')",
                id,account.username(),account.passwordHash(),properties.learningTimezone());
            jdbc.update("insert into account_name(normalized,user_id) values(?,?)",AccountRules.normalize(account.username()),id);
        }
        jdbc.update("insert into preset_identity(id,user_id) values(1,?)",id);
    }
}
