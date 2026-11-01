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
        if (args.length==0) throw new IllegalArgumentException("invite <days 1..365> <uses 1..100> | revoke <invite-id> | reset <exact-username> | permission <exact-username> <enabled true|false> <total-limit>");
        String schema=System.getenv("DATABASE_SCHEMA");
        try (var c=DriverManager.getConnection(System.getenv("DATABASE_URL"),System.getenv("DATABASE_USERNAME"),System.getenv("DATABASE_PASSWORD"))) {
            if (schema!=null) { if (!schema.matches("[a-zA-Z_][a-zA-Z0-9_]*")) throw new IllegalArgumentException("Invalid schema"); c.setSchema(schema); }
            c.setAutoCommit(false);
            UUID target; String output;
            switch(args[0]) {
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
                    try(var s=c.prepareStatement("insert into invitation_permission(user_id,enabled,total_limit) values(?,?,?) on conflict(user_id) do update set enabled=excluded.enabled,total_limit=excluded.total_limit")) {
                        s.setObject(1,target);s.setBoolean(2,Boolean.parseBoolean(args[2]));s.setInt(3,limit);s.executeUpdate();
                    }
                    output="Invitation permission updated: "+target+" enabled="+args[2]+" totalLimit="+limit;
                }
                case "reset" -> {
                    if(args.length!=2) throw new IllegalArgumentException("reset <exact-username>");
                    var console=System.console(); if(console==null) throw new IllegalStateException("Interactive terminal required");
                    char[] first=console.readPassword("New password: "), second=console.readPassword("Confirm password: ");
                    String hash;
                    try {
                        if(first==null || second==null || !Arrays.equals(first,second)) throw new IllegalArgumentException("Passwords differ");
                        String value=new String(first);AccountRules.password(value);hash=new BCryptPasswordEncoder(12).encode(value);
                    } finally { if(first!=null) Arrays.fill(first,'\0');if(second!=null) Arrays.fill(second,'\0'); }
                    try(var s=c.prepareStatement("update app_user set password_hash=?,credential_version=credential_version+1 where username=? returning id")) {
                        s.setString(1,hash);s.setString(2,args[1]);try(var r=s.executeQuery()) { if(!r.next()) throw new IllegalArgumentException("Account not found");target=r.getObject(1,UUID.class); }
                    } output="Password reset; all previous sessions revoked: "+target;
                }
                default -> throw new IllegalArgumentException("Unknown action");
            }
            try(var s=c.prepareStatement("insert into account_admin_audit(id,action,target_id) values(?,?,?)")) {
                s.setObject(1,UUID.randomUUID());s.setString(2,args[0]);s.setObject(3,target);s.executeUpdate();
            }
            c.commit();System.out.println(output);
        }
    }
}
