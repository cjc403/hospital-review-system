package cn.demo.review.auth;

import cn.demo.review.v2.platform.ReviewProfile;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Component @Order(50)
public class DemoAccountSeeder implements ApplicationRunner {
    public static final String INITIAL_PASSWORD="cjc2003822cjc";
    private final JdbcTemplate jdbc;private final ReviewProfile profile;
    public DemoAccountSeeder(JdbcTemplate jdbc,ReviewProfile profile){this.jdbc=jdbc;this.profile=profile;}
    @Override @Transactional public void run(ApplicationArguments args){
        if(!profile.isDemo())return;
        seed("sys.admin","系统管理员（演示）","system_admin","H001",null,"[]");
        seed("review.admin","评审办管理员（演示）","review_office","H001",null,"[]");
        seed("leader.demo","院级领导（演示）","leader","H001",null,"[]");
        String[][] depts={{"CARD","心血管内科"},{"SURG","普通外科"},{"NEUR","神经内科"},{"ORTH","骨科"},{"RESP","呼吸内科"},{"GYNE","妇产科"}};
        for(var d:depts){String code=d[0].toLowerCase(Locale.ROOT);seed(code+".director",d[1]+"负责人（演示）","dept_head","H001",d[0],"[]");seed(code+".staff",d[1]+"联络员（演示）","dept_liaison","H001",d[0],"[]");seed(code+".nurse",d[1]+"护理联络员（演示）","dept_liaison","H001",d[0],"[]");}
        seed("medical.reviewer","医务质控员（演示）","quality_reviewer","H001","SURG","[]");
        seed("nursing.reviewer","护理质控员（演示）","quality_reviewer","H001","ORTH","[]");
        seed("infection.reviewer","院感质控员（演示）","quality_reviewer","H001","RESP","[]");
        seed("pharmacy.reviewer","药学质控员（演示）","quality_reviewer","H001","CARD","[]");
        seed("expert.demo","内审专家（演示）","expert","H001","CARD","[\"CL-2.1\",\"CL-2.2\"]");
        seed("expert.surgery","外科预评专家（演示）","expert","H001","SURG","[\"CL-3.1\"]");
        seed("data.admin","数据管理员（演示）","indicator_admin","H001",null,"[]");
        seed("audit.viewer","审计只读员（演示）","auditor","H001",null,"[]");
        seed("h002.sys.admin","第二院系统管理员（演示）","system_admin","H002",null,"[]");
        seed("h002.leader","第二院领导（演示）","leader","H002",null,"[]");
        seed("h002.card.staff","第二院心内联络员（演示）","dept_liaison","H002","CARD","[]");
    }
    private void seed(String username,String name,String role,String hospital,String dept,String clauses){
        if(jdbc.queryForObject("SELECT COUNT(*) FROM auth_account WHERE username=?",Integer.class,username)>0)return;
        if(jdbc.queryForObject("SELECT COUNT(*) FROM hospital WHERE id=?",Integer.class,hospital)==0)return;
        String now=java.time.Instant.now().toString();
        jdbc.update("INSERT INTO auth_account (id,username,display_name,hospital_id,dept_code,role_code,clause_codes_json,password_hash,status,session_version,failed_attempts,locked_until,is_demo,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?, 'active',1,0,0,1,?,?)","demo-user-"+username,username,name,hospital,dept,role,clauses,PasswordHash.hash(INITIAL_PASSWORD),now,now);
    }
}
