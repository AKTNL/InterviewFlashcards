package com.interviewflashcards.tools;

import com.interviewflashcards.auth.AccountRules;
import java.security.SecureRandom;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Offline maintenance entry point. Does not boot HTTP, provision accounts, or start workers. */
public final class AccountAdmin {
    public static void main(String[] args) throws Exception {
        if (args.length==0) throw new IllegalArgumentException("invite <days 1..365> <uses 1..100> | revoke <invite-id> | reset <exact-username> | permission <exact-username> <enabled true|false> <total-limit> | create-admin <new-username> | role <exact-username> <ADMIN|USER>");
        String schema=System.getenv("DATABASE_SCHEMA");
        try (var c=DriverManager.getConnection(System.getenv("DATABASE_URL"),System.getenv("DATABASE_USERNAME"),System.getenv("DATABASE_PASSWORD"))) {
            if (schema!=null) { if (!schema.matches("[a-zA-Z_][a-zA-Z0-9_]*")) throw new IllegalArgumentException("Invalid schema"); c.setSchema(schema); }
            c.setAutoCommit(false);
            try(var s=c.createStatement()) { s.executeQuery("select id from registration_guard where id=1 for update").close(); }
            UUID target=null; String output; String before="{}",after="{}";
            try {
            switch(args[0]) {
                case "create-admin" -> {
                    if(args.length!=2)throw new IllegalArgumentException("create-admin <new-username>");
                    String name=AccountRules.username(args[1]);
                    int max=Integer.parseInt(Optional.ofNullable(System.getenv("REGISTRATION_MAX_ACCOUNTS")).orElse("100"));
                    try(var q=c.createStatement();var r=q.executeQuery("select (select count(*) from registration_use)+(select count(*) from admin_account_allocation)")) {
                        r.next();if(r.getLong(1)>=max)throw new IllegalStateException("Account capacity exhausted");
                    }
                    try(var q=c.prepareStatement("select count(*) from account_name where normalized=?")) {
                        q.setString(1,name);try(var r=q.executeQuery()){r.next();if(r.getInt(1)>0)throw new IllegalArgumentException("Account exists; use explicit role binding instead; credentials unchanged");}
                    }
                    String hash=readPassword();target=UUID.randomUUID();
                    try(var q=c.prepareStatement("insert into app_user(id,username,password_hash,account_origin,role) values(?,?,?,'ADMIN_CREATED','ADMIN')")) {
                        q.setObject(1,target);q.setString(2,name);q.setString(3,hash);q.executeUpdate();
                    }
                    try(var q=c.prepareStatement("insert into account_name(normalized,user_id) values(?,?)")) {q.setString(1,name);q.setObject(2,target);q.executeUpdate();}
                    try(var q=c.prepareStatement("insert into learning_preference(id,user_id) values(?,?)")) {q.setObject(1,UUID.randomUUID());q.setObject(2,target);q.executeUpdate();}
                    try(var q=c.prepareStatement("insert into admin_account_allocation(user_id) values(?)")) {q.setObject(1,target);q.executeUpdate();}
                    after="{\"role\":\"ADMIN\",\"origin\":\"ADMIN_CREATED\"}";
                    output="Independent administrator created: "+target;
                }
                case "role" -> {
                    if(args.length!=3 || !Set.of("ADMIN","USER").contains(args[2]))throw new IllegalArgumentException("role <exact-username> <ADMIN|USER>");
                    String previous;boolean enabled;
                    try(var q=c.prepareStatement("select id,role,enabled from app_user where username=? for no key update")) {
                        q.setString(1,args[1]);try(var r=q.executeQuery()){if(!r.next())throw new IllegalArgumentException("Account not found");target=r.getObject(1,UUID.class);previous=r.getString(2);enabled=r.getBoolean(3);}
                    }
                    if(previous.equals("ADMIN") && enabled && args[2].equals("USER")) {
                        try(var q=c.createStatement();var r=q.executeQuery("select count(*) from app_user where role='ADMIN' and enabled")){r.next();if(r.getLong(1)<=1)throw new IllegalStateException("Cannot remove last enabled administrator");}
                    }
                    if(args[2].equals("ADMIN") && !enabled)throw new IllegalArgumentException("Enable ordinary account before role grant");
                    try(var q=c.prepareStatement("update app_user set role=?,credential_version=credential_version+1,admin_version=admin_version+1 where id=?")){q.setString(1,args[2]);q.setObject(2,target);q.executeUpdate();}
                    before="{\"role\":\""+previous+"\"}";after="{\"role\":\""+args[2]+"\"}";
                    output="Role updated; prior sessions revoked: "+target;
                }
                case "invite" -> {
                    if(args.length!=3) throw new IllegalArgumentException("invite <days> <uses>");
                    int days=Integer.parseInt(args[1]), uses=Integer.parseInt(args[2]);
                    if(days<1 || days>365 || uses<1 || uses>100) throw new IllegalArgumentException("Invalid limits");
                    byte[] entropy=new byte[32]; new SecureRandom().nextBytes(entropy);
                    String token=Base64.getUrlEncoder().withoutPadding().encodeToString(entropy); target=UUID.randomUUID();
                    try(var s=c.prepareStatement("insert into registration_invite(id,token_digest,expires_at,max_uses) values(?,?,?,?)")) {
                        s.setObject(1,target);s.setString(2,AccountRules.digest(token));s.setTimestamp(3,Timestamp.from(Instant.now().plus(Duration.ofDays(days))));s.setInt(4,uses);s.executeUpdate();
                    }
                    output="Invite ID: "+target+"\nToken (shown once): "+token;
                }
                case "revoke" -> {
                    if(args.length!=2) throw new IllegalArgumentException("revoke <invite-id>"); target=UUID.fromString(args[1]);
                    try(var s=c.prepareStatement("update registration_invite set revoked=true where id=?")) {
                        s.setObject(1,target);if(s.executeUpdate()!=1) throw new IllegalArgumentException("Invite not found");
                    } output="Invite revoked: "+target;
                }
                case "permission" -> {
                    if(args.length!=4 || !Set.of("true","false").contains(args[2])) throw new IllegalArgumentException("permission <exact-username> <true|false> <total-limit>");
                    int limit=Integer.parseInt(args[3]);if(limit<0 || limit>10000) throw new IllegalArgumentException("Limit must be 0..10000");
                    try(var s=c.createStatement()) { s.executeQuery("select id from registration_guard where id=1 for update").close(); }
                    try(var s=c.prepareStatement("select id from app_user where username=?")) {
                        s.setString(1,args[1]);try(var r=s.executeQuery()) { if(!r.next()) throw new IllegalArgumentException("Account not found");target=r.getObject(1,UUID.class); }
                    }
                    try(var q=c.prepareStatement("select json_build_object('enabled',enabled,'totalLimit',total_limit,'successfulCount',successful_count)::text from invitation_permission where user_id=?")) {
                        q.setObject(1,target);try(var r=q.executeQuery()){if(r.next())before=r.getString(1);}
                    }
                    try(var s=c.prepareStatement("insert into invitation_permission(user_id,enabled,total_limit) values(?,?,?) on conflict(user_id) do update set enabled=excluded.enabled,total_limit=excluded.total_limit")) {
                        s.setObject(1,target);s.setBoolean(2,Boolean.parseBoolean(args[2]));s.setInt(3,limit);s.executeUpdate();
                    }
                    try(var q=c.prepareStatement("update app_user set admin_version=admin_version+1 where id=?")){q.setObject(1,target);q.executeUpdate();}
                    if(!Boolean.parseBoolean(args[2]))try(var q=c.prepareStatement("update registration_invite set revoked=true where inviter_id=? and not revoked and used_count<max_uses")){q.setObject(1,target);q.executeUpdate();}
                    after="{\"enabled\":"+args[2]+",\"totalLimit\":"+limit+"}";
                    output="Invitation permission updated: "+target+" enabled="+args[2]+" totalLimit="+limit;
                }
                case "reset" -> {
                    if(args.length!=2) throw new IllegalArgumentException("reset <exact-username>");
                    String hash=readPassword();
                    try(var s=c.prepareStatement("update app_user set password_hash=?,credential_version=credential_version+1,must_change_password=true,admin_version=admin_version+1 where username=? returning id")) {
                        s.setString(1,hash);s.setString(2,args[1]);try(var r=s.executeQuery()) { if(!r.next()) throw new IllegalArgumentException("Account not found");target=r.getObject(1,UUID.class); }
                    } after="{\"mustChangePassword\":true,\"sessionsRevoked\":true}"; output="Password reset; all previous sessions revoked: "+target;
                }
                default -> throw new IllegalArgumentException("Unknown action");
            }
            try(var s=c.prepareStatement("insert into account_admin_audit(id,action,target_id,before_value,after_value) values(?,?,?,?::jsonb,?::jsonb)")) {
                s.setObject(1,UUID.randomUUID());s.setString(2,args[0]);s.setObject(3,target);s.setString(4,before);s.setString(5,after);s.executeUpdate();
            }
            c.commit();System.out.println(output);
            } catch(Exception failure) {
                c.rollback();
                // Maintenance identity is the authenticated host operator, never inferred from a username.
                try(var q=c.prepareStatement("insert into account_admin_audit(id,action,target_id,result) values(?,?,?,'FAILED')")) {
                    q.setObject(1,UUID.randomUUID());q.setString(2,Set.of("invite","revoke","permission","reset","role","create-admin").contains(args[0])?args[0]:"invalid-command");q.setObject(3,target==null?new UUID(0,0):target);q.executeUpdate();c.commit();
                }
                throw failure;
            }
        }
    }
    private static String readPassword() {
        var console=System.console();if(console==null)throw new IllegalStateException("Interactive terminal required");
        char[] first=console.readPassword("New password: "),second=console.readPassword("Confirm password: ");
        try {
            if(first==null || second==null || !Arrays.equals(first,second))throw new IllegalArgumentException("Passwords differ");
            String value=new String(first);AccountRules.password(value);return new BCryptPasswordEncoder(12).encode(value);
        } finally {if(first!=null)Arrays.fill(first,'\0');if(second!=null)Arrays.fill(second,'\0');}
    }
}
