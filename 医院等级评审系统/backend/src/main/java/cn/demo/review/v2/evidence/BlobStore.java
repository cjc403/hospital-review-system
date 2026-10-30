package cn.demo.review.v2.evidence;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 材料内容存储接口。当前只有本地目录实现；接入院内对象存储（S3 兼容）时新增实现即可，
 * 业务层只认 storageKey，前端永远拿不到真实路径。
 */
public interface BlobStore {
    String put(byte[] content);
    byte[] get(String key);

    @Component
    class LocalBlobStore implements BlobStore {
        private final Path root;

        public LocalBlobStore(@Value("${review.storage-dir:./.runtime/v2-evidence}") String dir) {
            this.root = Path.of(dir).toAbsolutePath().normalize();
            try {
                Files.createDirectories(root);
            } catch (IOException e) {
                throw new UncheckedIOException("材料存储目录不可用", e);
            }
        }

        @Override
        public String put(byte[] content) {
            String key = UUID.randomUUID().toString();
            Path target = root.resolve(key).normalize();
            if (!target.startsWith(root)) throw new IllegalStateException("存储键不合法");
            try {
                Files.write(target, content);
                return key;
            } catch (IOException e) {
                throw new UncheckedIOException("材料写入失败", e);
            }
        }

        @Override
        public byte[] get(String key) {
            if (key == null || !key.matches("[A-Za-z0-9-]{8,64}")) throw new IllegalStateException("存储键不合法");
            Path target = root.resolve(key).normalize();
            if (!target.startsWith(root) || !Files.isRegularFile(target)) {
                throw new IllegalStateException("材料内容不存在");
            }
            try {
                return Files.readAllBytes(target);
            } catch (IOException e) {
                throw new UncheckedIOException("材料读取失败", e);
            }
        }
    }
}
