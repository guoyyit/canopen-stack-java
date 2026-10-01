package com.github.guoyyit.canopenstackjava.persist;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 基于 JSON 文件的节点快照持久化。
 * 保存 NMT 状态、对象字典数据与紧急报文历史，用于重启后一致性恢复。
 */
public final class PersistenceService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path filePath;

    public PersistenceService(String filePath) {
        this.filePath = Path.of(filePath);
    }

    public void save(Map<Integer, NodeSnapshot> snapshots) {
        try {
            Path parent = filePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), snapshots);
            Files.move(temp, filePath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Map<Integer, NodeSnapshot> load() {
        if (!Files.exists(filePath)) {
            return Map.of();
        }
        try {
            Map<Integer, NodeSnapshot> loaded = MAPPER.readValue(filePath.toFile(),
                    new TypeReference<HashMap<Integer, NodeSnapshot>>() {
                    });
            return loaded == null ? Map.of() : loaded;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void clear() {
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
