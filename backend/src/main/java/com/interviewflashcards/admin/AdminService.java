package com.interviewflashcards.admin;

import com.interviewflashcards.auth.*;
import com.interviewflashcards.common.config.AppProperties;
import com.interviewflashcards.common.web.*;
import com.interviewflashcards.files.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AdminService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final PersonalInviteService invites;
    private final RegistrationProperties registration;
    private final AppProperties app;
    private final CapacityService capacity;
    private final CapacityProperties limits;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    public AdminService(JdbcTemplate jdbc,PasswordEncoder passwords,PersonalInviteService invites,RegistrationProperties registration,
                        AppProperties app,CapacityService capacity,CapacityProperties limits,ObjectMapper mapper,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.passwords=passwords;this.invites=invites;this.registration=registration;this.app=app;
        this.capacity=capacity;this.limits=limits;this.mapper=mapper;this.tx=new TransactionTemplate(manager);
    }
    private void authorize(UUID actor) {
        var rows=jdbc.queryForList("select role,enabled,must_change_password from app_user where id=?",actor);
        if(rows.isEmpty() || !Boolean.TRUE.equals(rows.getFirst().get("enabled")) || Boolean.TRUE.equals(rows.getFirst().get("must_change_password"))
            || !Capabilities.forRole((String)rows.getFirst().get("role")).contains(Capabilities.USERS)) throw new ApiFailure(ErrorCode.FORBIDDEN);
    }
    private <T> T write(UUID actor,UUID target,String action,Supplier<T> body) {
        try {
            return tx.execute(status->{invites.lock();authorize(actor);return body.get();});
        } catch(RuntimeException failure) {
            // Separate transaction records rejection/rollback without storing request bodies or credentials.
            tx.executeWithoutResult(status->audit(actor,target,action,Map.of(),Map.of(),failure instanceof ApiFailure f?f.code().name():"FAILED"));
            throw failure;
        }
    }
    private void audit(UUID actor,UUID target,String action,Object before,Object after,String result) {
        try { jdbc.update("insert into account_admin_audit(id,actor_id,actor_source,target_id,action,before_value,after_value,result) values(?,?,'WEB',?,?,?::jsonb,?::jsonb,?)",
            UUID.randomUUID(),actor,target,action,mapper.writeValueAsString(before),mapper.writeValueAsString(after),result); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
    }
    private Map<String,Object> target(UUID id,long version) {
        var rows=jdbc.queryForList("select id,role,enabled,admin_version,must_change_password from app_user where id=? and space_kind='PERSONAL' for no key update",id);
        if(rows.isEmpty()) throw new ApiFailure(ErrorCode.NOT_FOUND);
        var row=rows.getFirst();
        if(!"USER".equals(row.get("role"))) throw new ApiFailure(ErrorCode.FORBIDDEN);
        if(((Number)row.get("admin_version")).longValue()!=version) throw new ApiFailure(ErrorCode.VERSION_CONFLICT);
        return row;
    }
    private static final String FIELDS="u.id,u.username,u.nickname,u.role,u.enabled,u.created_at,u.last_login_at,u.account_origin,u.must_change_password,u.admin_version,coalesce(p.enabled,false) invitation_enabled,coalesce(p.total_limit,0) total_limit,coalesce(p.successful_count,0) successful_count,greatest(0,coalesce(p.total_limit,0)-coalesce(p.successful_count,0)) remaining_quota";
    public Map<String,Object> users(UUID actor,String search,String state,String invitation,int page,int size) {
        authorize(actor);
        if(search.length()>100 || page<0 || page>100000 || size<1 || size>50 || !Set.of("ALL","ENABLED","DISABLED").contains(state) || !Set.of("ALL","ENABLED","DISABLED").contains(invitation)) throw new ApiFailure(ErrorCode.VALIDATION_ERROR);
        String where=" from app_user u left join invitation_permission p on p.user_id=u.id where u.space_kind='PERSONAL' and (position(lower(?) in lower(u.username))>0 or position(lower(?) in lower(coalesce(u.nickname,'')))>0) and (?='ALL' or u.enabled=(?='ENABLED')) and (?='ALL' or coalesce(p.enabled,false)=(?='ENABLED'))";
        Object[] args={search,search,state,state,invitation,invitation};
        long total=jdbc.queryForObject("select count(*)"+where,Long.class,args);
        var parameters=new ArrayList<Object>(Arrays.asList(args));parameters.add(size);parameters.add(page*size);
        var users=jdbc.queryForList("select "+FIELDS+where+" order by u.created_at desc,u.id limit ? offset ?",parameters.toArray());
        for(var row:users) row.put("storage",capacity.usage((UUID)row.get("id")));
        return Map.of("items",users,"total",total,"page",page,"size",size);
    }
    public Map<String,Object> detail(UUID actor,UUID id) {
        authorize(actor);
        var rows=jdbc.queryForList("select "+FIELDS+" from app_user u left join invitation_permission p on p.user_id=u.id where u.id=? and u.space_kind='PERSONAL'",id);
        if(rows.isEmpty())throw new ApiFailure(ErrorCode.NOT_FOUND);
        var row=rows.getFirst();row.put("storage",capacity.usage(id));
        row.put("invites",jdbc.queryForList("select id,expires_at,used_count,max_uses,revoked,case when revoked then 'REVOKED' when used_count>=max_uses then 'USED' when expires_at<=current_timestamp then 'EXPIRED' else 'AVAILABLE' end status from registration_invite where inviter_id=? order by created_at desc limit 50",id));
        row.put("audit",jdbc.queryForList("select id,actor_id,actor_source,action,before_value::text before_value,after_value::text after_value,result,created_at from account_admin_audit where target_id=? order by created_at desc,id limit 30",id));
        return row;
    }
    public Map<String,Object> configuration(UUID actor) {
        authorize(actor);
        return Map.of("registrationEnabled",registration.enabled(),"maxAccounts",registration.maxAccounts(),
            "allocatedAccounts",jdbc.queryForObject("select (select count(*) from registration_use)+(select count(*) from admin_account_allocation)",Long.class),
            "capacity",limits,"configurationChannel","环境配置；见账号维护手册。页面只读。", "capabilities",Capabilities.forRole("ADMIN"));
    }
    public UUID create(UUID actor,UUID operation,String raw,String password,String nickname) {
        return write(actor,operation,"CREATE_USER",()->{
            String name=AccountRules.username(raw);AccountRules.password(password);
            if(nickname!=null && (nickname.length()>60 || nickname.codePoints().anyMatch(Character::isISOControl)))throw new ApiFailure(ErrorCode.VALIDATION_ERROR);
            String fingerprint=AccountRules.digest(name+"\n"+Objects.toString(nickname,"")+"\n"+password);
            var receipts=jdbc.queryForList("select * from admin_create_receipt where operation_id=?",operation);
            if(!receipts.isEmpty()) {
                var receipt=receipts.getFirst();
                if(!actor.equals(receipt.get("actor_id")) || !passwords.matches(fingerprint,(String)receipt.get("request_hash")))throw new ApiFailure(ErrorCode.OPERATION_ID_REUSED);
                return (UUID)receipt.get("target_id");
            }
            invites.checkOpen();
            if(name.equals(AccountRules.normalize(app.account().username())) || jdbc.queryForObject("select count(*) from account_name where normalized=?",Long.class,name)>0)throw new ApiFailure(ErrorCode.USERNAME_TAKEN);
            UUID id=UUID.randomUUID();
            try {
                jdbc.update("insert into app_user(id,username,password_hash,nickname,learning_timezone,account_origin,must_change_password) values(?,?,?,?,?,'ADMIN_CREATED',true)",id,name,passwords.encode(password),nickname==null || nickname.isBlank()?null:nickname.strip(),app.learningTimezone());
                jdbc.update("insert into account_name(normalized,user_id) values(?,?)",name,id);
            } catch(org.springframework.dao.DuplicateKeyException e){throw new ApiFailure(ErrorCode.USERNAME_TAKEN);}
            jdbc.update("insert into learning_preference(id,user_id) values(?,?)",UUID.randomUUID(),id);
            jdbc.update("insert into admin_account_allocation(user_id) values(?)",id);
            jdbc.update("insert into admin_create_receipt(operation_id,actor_id,target_id,request_hash) values(?,?,?,?)",operation,actor,id,passwords.encode(fingerprint));
            audit(actor,id,"CREATE_USER",Map.of(),Map.of("username",name,"origin","ADMIN_CREATED","mustChangePassword",true),"SUCCESS");
            return id;
        });
    }
    public void status(UUID actor,UUID id,long version,boolean enabled) {
        write(actor,id,"USER_STATUS",()->{
            var before=target(id,version);
            jdbc.update("update app_user set enabled=?,credential_version=credential_version+1,admin_version=admin_version+1 where id=?",enabled,id);
            if(!enabled)jdbc.update("update registration_invite set revoked=true where inviter_id=? and not revoked and used_count<max_uses",id);
            audit(actor,id,"USER_STATUS",before,Map.of("enabled",enabled),"SUCCESS");return true;
        });
    }
    public void permission(UUID actor,UUID id,long version,boolean enabled,int total) {
        write(actor,id,"INVITE_PERMISSION",()->{
            target(id,version);if(total<0 || total>10000)throw new ApiFailure(ErrorCode.VALIDATION_ERROR);
            var before=jdbc.queryForList("select enabled,total_limit,successful_count from invitation_permission where user_id=?",id);
            jdbc.update("insert into invitation_permission(user_id,enabled,total_limit) values(?,?,?) on conflict(user_id) do update set enabled=excluded.enabled,total_limit=excluded.total_limit",id,enabled,total);
            if(!enabled)jdbc.update("update registration_invite set revoked=true where inviter_id=? and not revoked and used_count<max_uses",id);
            jdbc.update("update app_user set admin_version=admin_version+1 where id=?",id);
            audit(actor,id,"INVITE_PERMISSION",before,Map.of("enabled",enabled,"totalLimit",total),"SUCCESS");return true;
        });
    }
    public void revoke(UUID actor,UUID id,long version,UUID invite) {
        write(actor,id,"REVOKE_INVITE",()->{
            target(id,version);
            if(jdbc.update("update registration_invite set revoked=true where id=? and inviter_id=? and used_count<max_uses",invite,id)!=1)throw new ApiFailure(ErrorCode.NOT_FOUND);
            jdbc.update("update app_user set admin_version=admin_version+1 where id=?",id);
            audit(actor,id,"REVOKE_INVITE",Map.of(),Map.of("inviteId",invite),"SUCCESS");return true;
        });
    }
    public void reset(UUID actor,UUID id,long version,String password) {
        write(actor,id,"RESET_PASSWORD",()->{
            target(id,version);AccountRules.password(password);
            jdbc.update("update app_user set password_hash=?,credential_version=credential_version+1,must_change_password=true,admin_version=admin_version+1 where id=?",passwords.encode(password),id);
            audit(actor,id,"RESET_PASSWORD",Map.of(),Map.of("mustChangePassword",true,"sessionsRevoked",true),"SUCCESS");return true;
        });
    }
}
