package cn.demo.review.persistence;

import cn.demo.review.domain.Model.State;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.Optional;

/** One atomic aggregate per local demo; not a production clinical schema. */
@Repository
public class SnapshotRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    public SnapshotRepository(JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.json=json;this.transactions=new TransactionTemplate(manager);
        jdbc.execute("CREATE TABLE IF NOT EXISTS demo_snapshot (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)");
    }
    public Optional<State> load() {
        return jdbc.query("SELECT payload FROM demo_snapshot WHERE id=1",(rs,row)->decode(rs.getString(1))).stream().findFirst();
    }
    public void save(State state) {
        String payload=encode(state);
        // 先 UPDATE 再 INSERT：H2 与 PostgreSQL 都可用，不依赖 MERGE 方言。
        // 调用方（DemoService）为单写者，因此不存在并发插入竞态。
        transactions.executeWithoutResult(status->{
            int updated=jdbc.update("UPDATE demo_snapshot SET payload=? WHERE id=1",payload);
            if(updated==0)jdbc.update("INSERT INTO demo_snapshot (id,payload) VALUES (1,?)",payload);
        });
    }
    private State decode(String value) {try{return json.readValue(value,State.class);}catch(JsonProcessingException e){throw new IllegalStateException("演示状态读取失败，请保留数据库排查",e);}}
    private String encode(State value) {try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("演示状态序列化失败",e);}}
}
